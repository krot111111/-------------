package ru.uniride;

import java.util.ArrayList;
import java.util.List;

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
    public List<User> participants = new ArrayList<>();

    public Ride() {}

    public boolean isValid() {
        if (id == null || creator == null || creator.trim().isEmpty()) return false;
        if (departure == null || departure.trim().isEmpty()) return false;
        if (destination == null || destination.trim().isEmpty()) return false;
        if (time == null || time.trim().isEmpty()) return false;
        if (totalSeats == null || totalSeats < 1) return false;
        return true;
    }
}
