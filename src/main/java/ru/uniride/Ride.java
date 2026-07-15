package ru.uniride;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Ride {
    public Long id;
    public String creator;
    public String departure;
    public String destination;
    public String time;
    public Integer totalSeats;
    public String status;
    public Double lat;
    public Double lon;
    public List<User> participants = new ArrayList<>();

    // Реальные дата+время начала (сервер сам вычисляет при создании/переносе) -
    // нужны, чтобы корректно считать "прошло 40 минут", даже если перенос увёл время за полночь.
    // Клиенту не отдаём - он ориентируется на строку time.
    @JsonIgnore
    public LocalDateTime scheduledAt;

    public Ride() {}

    public boolean isValid() {
        if (id == null || creator == null || creator.trim().isEmpty()) return false;
        if (departure == null || departure.trim().isEmpty()) return false;
        if (destination == null || destination.trim().isEmpty()) return false;
        if (time == null || time.trim().isEmpty()) return false;
        // Верхняя граница - 3 (значит всего в поездке максимум 4 человека вместе с организатором),
        // соответствует тому, что предлагает интерфейс. Без этого можно было прислать
        // произвольно большое число мест в обход выпадающего списка.
        if (totalSeats == null || totalSeats < 1 || totalSeats > 3) return false;
        return true;
    }
}
