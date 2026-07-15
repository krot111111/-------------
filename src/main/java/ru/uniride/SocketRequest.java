package ru.uniride;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SocketRequest {
    public String type;
    public Ride rideData; // Used for createRide
    public Long rideId;   // Used for joinRide/leaveRide/postponeRide
    public User user;     // Used for joinRide (id/name are overwritten server-side from studentToken)

    // Registration & moderation
    public String firstName;    // Used for registerStudent
    public String lastName;     // Used for registerStudent
    public String groupNumber;  // Used for registerStudent
    public String phoneNumber;  // Used for registerStudent
    public String gradebookNumber; // Used for registerStudent/studentLogin
    public String password;     // Used for registerStudent/studentLogin
    // Личность для действий с поездками (createRide/joinRide/leaveRide/postponeRide/identify)
    // и для checkStatus - сервер доверяет только этому токену, не клиентским id/creator/userId
    public String studentToken;
    public Long studentId;      // Target student's id - admin actions (approve/reject/revoke) OR
                                 // organizer actions on their own ride (markAttendance/kickParticipant)
    public Boolean attended;    // Used for markAttendance: true = был, false = не пришёл
    public String adminUsername; // Used for adminLogin
    public String adminPassword; // Used for adminLogin
    public String adminToken;    // Used for getAllUsers/approveStudent/rejectStudent/revokeAccess/adminLogout

    public SocketRequest() {}
}
