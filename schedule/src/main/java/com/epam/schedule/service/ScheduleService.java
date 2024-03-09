package com.epam.schedule.service;

import com.epam.schedule.domain.Trainer;
import com.epam.schedule.dto.Month;
import com.epam.schedule.dto.Schedule;
import com.epam.schedule.dto.Years;
import com.epam.schedule.repository.ScheduleRepository;
import com.epam.schedule.repository.TrainerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.jms.Queue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ScheduleService {
    private final ScheduleRepository scheduleRepository;
    private final TrainerRepository trainerRepository;
    private final TrainerService trainerService;
    private final Queue queue;
    private final JmsTemplate jmsTemplate;
    private static final Logger logger = LogManager.getLogger(TrainerService.class);


    @Autowired
    public ScheduleService(ScheduleRepository scheduleRepository, TrainerRepository trainerRepository, TrainerService trainerService, Queue queue, JmsTemplate jmsTemplate) {
        this.scheduleRepository = scheduleRepository;
        this.trainerRepository = trainerRepository;
        this.trainerService = trainerService;
        this.queue = queue;
        this.jmsTemplate = jmsTemplate;
    }

    public void save(String username) {
        List<Trainer> trainers = findTrainersByUsername(username);
        List<Years> years = generateYearsList(trainers);
        populateMonthsData(trainers, years);
        logger.info("Schedule for trainer with username " + username + " returned");
        scheduleRepository.insert(buildSchedule(trainers.get(0), years));
    }

    public Schedule getSchedule(String username) {
        Schedule response = scheduleRepository.findByUsername(username);
        if (response == null) {
            throw new RuntimeException("Schedule for trainer with username " + username + " not found");
        }
        return response;
    }

    private List<Trainer> findTrainersByUsername(String username) {
        try {
            return trainerRepository.findAllByUsername(username);
        } catch (Exception e) {
            logger.error("Trainer with username " + username + " not found");
            throw new RuntimeException("Trainer with username " + username + " not found");
        }
    }

    private List<Years> generateYearsList(List<Trainer> trainers) {
        return trainers.stream()
                .map(trainer -> trainer.getDateTime().getYear() + 1900)
                .distinct()
                .map(year -> Years.builder().year(year).months(new ArrayList<>()).build())
                .collect(Collectors.toList());
    }

    private void populateMonthsData(List<Trainer> trainers, List<Years> years) {
        trainers.forEach(trainer ->
                years.stream()
                        .filter(year -> trainer.getDateTime().getYear() + 1900 == year.getYear())
                        .findFirst()
                        .ifPresent(year -> populateMonthData(trainer, year))
        );
    }

    private void populateMonthData(Trainer trainer, Years year) {
        int month = trainer.getDateTime().getMonth();
        String monthName = monthReporter(month);

        Month monthObject = year.getMonths().stream()
                .filter(m -> m.getMonth().equals(monthName))
                .findFirst()
                .orElseGet(() -> {
                    Month newMonth = Month.builder().month(monthName).summaryDuration(Duration.ZERO).build();
                    year.getMonths().add(newMonth);
                    return newMonth;
                });

        monthObject.setSummaryDuration(monthObject.getSummaryDuration().plus(trainer.getDuration()));
    }

    private Schedule buildSchedule(Trainer trainer, List<Years> years) {
        return Schedule.builder()
                .username(trainer.getUsername())
                .firstName(trainer.getFirstName())
                .lastName(trainer.getLastName())
                .status(trainer.getIsActive())
                .years(years)
                .build();
    }

    public String monthReporter(int n) {
        return switch (n) {
            case 1 -> "JANUARY";
            case 2 -> "FEBRUARY";
            case 3 -> "MARCH";
            case 4 -> "APRIL";
            case 5 -> "MAY";
            case 6 -> "JUNE";
            case 7 -> "JULY";
            case 8 -> "AUGUST";
            case 9 -> "SEPTEMBER";
            case 10 -> "OCTOBER";
            case 11 -> "NOVEMBER";
            case 12 -> "DECEMBER";
            default -> throw new RuntimeException("Invalid month number");
        };
    }


    public void updateSchedule(String username, Schedule schedule) {
        Schedule existingSchedule = getSchedule(username);
        if (existingSchedule == null) {
            throw new RuntimeException("Schedule for trainer with username " + username + " not found");
        }
        existingSchedule.setYears(schedule.getYears());
        existingSchedule.setFirstName(schedule.getFirstName());
        existingSchedule.setLastName(schedule.getLastName());
        existingSchedule.setStatus(schedule.getStatus());
        scheduleRepository.save(existingSchedule);
    }

    @JmsListener(destination = "finaldemo", id = "2")
    public void consumerUsername(String message) {
        logger.info("Received message: " + message);
        try {
            if (message.length() > 15) {
                trainerService.consumeMessage(message);
            } else {
                Schedule schedule = getSchedule(message);
                ObjectMapper mapper = new ObjectMapper();
                mapper.registerModule(new JavaTimeModule());
                String json;
                json = mapper.writeValueAsString(schedule);
                jmsTemplate.convertAndSend(queue, json);
            }
        } catch (Exception e) {
            logger.error("Error while converting schedule to json");
        }
    }
}
