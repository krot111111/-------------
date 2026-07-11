package ru.uniride;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SocketRequest {
    public String type;
    public Ride rideData; // Used for createRide
    public Long rideId;   // Used for joinRide/leaveRide
    public User user;     // Used for joinRide
    public String userId; // Used for leaveRide
    public Boolean isCreator; // Used for leaveRide

    public SocketRequest() {}
}
