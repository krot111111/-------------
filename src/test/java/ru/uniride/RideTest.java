package ru.uniride;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class RideTest {

    @Test
    void isValidAcceptsCompleteRideWithinSeatLimit() {
        Ride ride = validRide();

        assertTrue(ride.isValid());
    }

    @Test
    void isValidRejectsMissingRequiredFields() {
        assertInvalidWithMutation(r -> r.id = null);
        assertInvalidWithMutation(r -> r.creator = null);
        assertInvalidWithMutation(r -> r.creator = " ");
        assertInvalidWithMutation(r -> r.departure = null);
        assertInvalidWithMutation(r -> r.departure = " ");
        assertInvalidWithMutation(r -> r.destination = null);
        assertInvalidWithMutation(r -> r.destination = " ");
        assertInvalidWithMutation(r -> r.time = null);
        assertInvalidWithMutation(r -> r.time = " ");
        assertInvalidWithMutation(r -> r.totalSeats = null);
    }

    @Test
    void isValidRejectsSeatCountsOutsideSupportedRange() {
        assertInvalidWithMutation(r -> r.totalSeats = 0);
        assertInvalidWithMutation(r -> r.totalSeats = -1);
        assertInvalidWithMutation(r -> r.totalSeats = 4);
    }

    @Test
    void scheduledAtIsNotSerializedToClientJson() throws JsonProcessingException {
        Ride ride = validRide();
        ride.scheduledAt = LocalDateTime.of(2026, 7, 16, 12, 0);

        String json = new ObjectMapper().writeValueAsString(ride);

        assertFalse(json.contains("scheduledAt"));
        assertTrue(json.contains("\"time\":\"12:00\""));
    }

    private static void assertInvalidWithMutation(RideMutation mutation) {
        Ride ride = validRide();
        mutation.apply(ride);
        assertFalse(ride.isValid());
    }

    private static Ride validRide() {
        Ride ride = new Ride();
        ride.id = 1L;
        ride.creator = "student-1";
        ride.departure = "Campus";
        ride.destination = "Station";
        ride.time = "12:00";
        ride.totalSeats = 3;
        return ride;
    }

    private interface RideMutation {
        void apply(Ride ride);
    }
}
