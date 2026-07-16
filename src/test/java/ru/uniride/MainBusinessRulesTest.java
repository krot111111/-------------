package ru.uniride;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MainBusinessRulesTest {

    @Test
    void parseStudentIdAcceptsOnlyExpectedUserIds() {
        assertEquals(7L, Main.parseStudentId("student-7"));
        assertEquals(0L, Main.parseStudentId("student-0"));

        assertNull(Main.parseStudentId(null));
        assertNull(Main.parseStudentId(""));
        assertNull(Main.parseStudentId("7"));
        assertNull(Main.parseStudentId("student-"));
        assertNull(Main.parseStudentId("student-seven"));
        assertNull(Main.parseStudentId("teacher-7"));
    }

    @Test
    void resolveScheduledAtKeepsRecentPastOnSameDayButMovesOldPastToTomorrow() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 16, 12, 0);

        assertEquals(LocalDateTime.of(2026, 7, 16, 12, 30), Main.resolveScheduledAt("12:30", now));
        assertEquals(LocalDateTime.of(2026, 7, 16, 11, 30), Main.resolveScheduledAt("11:30", now));
        assertEquals(LocalDateTime.of(2026, 7, 17, 10, 30), Main.resolveScheduledAt("10:30", now));
    }

    @Test
    void shouldExpireRideOnlyAfterFortyMinutesPastStart() {
        Ride ride = ride(1L, "student-1", "ACTIVE", user("student-1", "Organizer"));
        LocalDateTime start = LocalDateTime.of(2026, 7, 16, 12, 0);
        ride.scheduledAt = start;

        assertFalse(Main.shouldExpireRide(ride, start.plusMinutes(40)));
        assertTrue(Main.shouldExpireRide(ride, start.plusMinutes(41)));

        ride.scheduledAt = null;
        assertFalse(Main.shouldExpireRide(ride, start.plusDays(1)));
    }

    @Test
    void isUserInActiveRideChecksOnlyActiveAndFullRides() {
        List<Ride> rides = List.of(
                ride(1L, "student-1", "ACTIVE", user("student-1", "One")),
                ride(2L, "student-2", "FULL", user("student-2", "Two")),
                ride(3L, "student-3", "CANCELLED", user("student-3", "Three"))
        );

        assertTrue(Main.isUserInActiveRide(rides, "student-1"));
        assertTrue(Main.isUserInActiveRide(rides, "student-2"));
        assertFalse(Main.isUserInActiveRide(rides, "student-3"));
        assertFalse(Main.isUserInActiveRide(rides, "student-4"));
    }

    @Test
    void removeUserFromRidesRemovesParticipantAndReopensRide() {
        Ride ride = ride(1L, "student-1", "FULL",
                user("student-1", "Organizer"),
                user("student-2", "Passenger"));
        List<Ride> rides = new ArrayList<>(List.of(ride));

        assertTrue(Main.removeUserFromRides(rides, "student-2"));

        assertEquals(1, rides.size());
        assertEquals("ACTIVE", ride.status);
        assertEquals(List.of("student-1"), ride.participants.stream().map(p -> p.id).toList());
    }

    @Test
    void removeUserFromRidesRemovesWholeRideWhenUserIsCreator() {
        Ride ride = ride(1L, "student-1", "ACTIVE",
                user("student-1", "Organizer"),
                user("student-2", "Passenger"));
        List<Ride> rides = new ArrayList<>(List.of(ride));

        assertTrue(Main.removeUserFromRides(rides, "student-1"));

        assertTrue(rides.isEmpty());
    }

    @Test
    void visibleRidesForParticipantsReturnsFullRideData() {
        Ride ride = rideWithPrivateData();
        List<Ride> visible = Main.visibleRidesFor(List.of(ride), 2L);

        assertSame(ride, visible.get(0));
        assertEquals(56.0, visible.get(0).lat);
        assertEquals("+70000000002", visible.get(0).participants.get(1).phone);
    }

    @Test
    void visibleRidesForNonParticipantsRedactsContactsAndMeetingPoint() {
        Ride ride = rideWithPrivateData();
        List<Ride> visible = Main.visibleRidesFor(List.of(ride), 3L);

        Ride redacted = visible.get(0);
        assertNotSame(ride, redacted);
        assertNull(redacted.lat);
        assertNull(redacted.lon);
        assertEquals(2, redacted.participants.size());
        assertEquals("Organizer", redacted.participants.get(0).name);
        assertEquals(4, redacted.participants.get(1).noShowCount);
        assertNull(redacted.participants.get(0).phone);
        assertNull(redacted.participants.get(0).tg);
        assertNull(redacted.participants.get(0).vk);
    }

    @Test
    void validateAttendanceMarkAllowsOrganizerToMarkPassengersAfterStart() {
        Ride ride = startedRide();

        assertNull(Main.validateAttendanceMark(ride, "student-1", 2L, false, ride.scheduledAt.plusMinutes(1)));
    }

    @Test
    void validateAttendanceMarkAllowsPassengerToMarkOrganizerAfterStart() {
        Ride ride = startedRide();

        assertNull(Main.validateAttendanceMark(ride, "student-2", 1L, false, ride.scheduledAt.plusMinutes(1)));
    }

    @Test
    void validateAttendanceMarkRejectsOrganizerMarkingSelf() {
        Ride ride = startedRide();

        assertEquals(
                "Организатора могут отмечать только попутчики",
                Main.validateAttendanceMark(ride, "student-1", 1L, false, ride.scheduledAt.plusMinutes(1))
        );
    }

    @Test
    void validateAttendanceMarkRejectsPassengerMarkingAnotherPassenger() {
        Ride ride = startedRide(user("student-1", "Organizer"), user("student-2", "Two"), user("student-3", "Three"));

        assertEquals(
                "Только организатор может отмечать явку участников",
                Main.validateAttendanceMark(ride, "student-2", 3L, false, ride.scheduledAt.plusMinutes(1))
        );
    }

    @Test
    void validateAttendanceMarkRejectsBeforeRideStarts() {
        Ride ride = startedRide();

        assertEquals(
                "Явку можно отмечать только после начала поездки",
                Main.validateAttendanceMark(ride, "student-1", 2L, false, ride.scheduledAt.minusSeconds(1))
        );
    }

    @Test
    void validateAttendanceMarkRejectsMissingPayloadAndUnknownTarget() {
        Ride ride = startedRide();

        assertEquals("Не указан участник или отметка", Main.validateAttendanceMark(ride, "student-1", null, false, ride.scheduledAt));
        assertEquals("Не указан участник или отметка", Main.validateAttendanceMark(ride, "student-1", 2L, null, ride.scheduledAt));
        assertEquals("Участник не найден", Main.validateAttendanceMark(ride, "student-1", 99L, false, ride.scheduledAt));
    }

    private static Ride rideWithPrivateData() {
        User organizer = user("student-1", "Organizer");
        organizer.phone = "+70000000001";
        organizer.tg = "@organizer";
        organizer.vk = "https://vk.com/organizer";

        User passenger = user("student-2", "Passenger");
        passenger.phone = "+70000000002";
        passenger.noShowCount = 4;

        Ride ride = ride(10L, "student-1", "ACTIVE", organizer, passenger);
        ride.lat = 56.0;
        ride.lon = 44.0;
        return ride;
    }

    private static Ride startedRide(User... users) {
        User[] participants = users.length == 0
                ? new User[]{user("student-1", "Organizer"), user("student-2", "Passenger")}
                : users;
        Ride ride = ride(1L, "student-1", "ACTIVE", participants);
        ride.scheduledAt = LocalDateTime.of(2026, 7, 16, 12, 0);
        return ride;
    }

    private static Ride ride(Long id, String creator, String status, User... participants) {
        Ride ride = new Ride();
        ride.id = id;
        ride.creator = creator;
        ride.departure = "Campus";
        ride.destination = "Station";
        ride.time = "12:00";
        ride.totalSeats = 3;
        ride.status = status;
        ride.participants = new ArrayList<>(List.of(participants));
        return ride;
    }

    private static User user(String id, String name) {
        User user = new User();
        user.id = id;
        user.name = name;
        return user;
    }
}
