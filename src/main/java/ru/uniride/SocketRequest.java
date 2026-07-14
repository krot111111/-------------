package ru.uniride;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SocketRequest {
    public String type;
    public Ride rideData; // Used for createRide
    public Long rideId;   // Used for joinRide/leaveRide
    public User user;     // Used for joinRide
    public String userId; // Used for leaveRide

    // Registration & moderation
    public String firstName;    // Used for registerStudent
    public String lastName;     // Used for registerStudent
    public String groupNumber;  // Used for registerStudent
    public String phoneNumber;  // Used for registerStudent
    public String gradebookNumber; // Used for registerStudent/studentLogin
    public String password;     // Used for registerStudent/studentLogin
    public String studentToken; // Used for checkStatus (identifies the session, not a guessable ID)
    public Long studentId;      // Used for approveStudent/rejectStudent/revokeAccess (admin-only actions)
    public String adminUsername; // Used for adminLogin
    public String adminPassword; // Used for adminLogin
    public String adminToken;    // Used for getAllUsers/approveStudent/rejectStudent/revokeAccess/adminLogout

    public SocketRequest() {}
}
