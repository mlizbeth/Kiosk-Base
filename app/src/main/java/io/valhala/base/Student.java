package io.valhala.base;

import java.text.SimpleDateFormat;
import java.util.Date;

public class Student {

    private String id;
    private String reason;
    private String timeStamp;

    public Student(String id, String reason) {
        this.id = id;
        this.reason = reason;
        this.setTimeStamp();
    }

    public Student() {}

    public void setId(String id) {this.id = id;}

    public String getId() {return id;}

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp() {
        timeStamp = new SimpleDateFormat("MM/dd/YYYY HH:mm:ss").format(new Date());
    }

    public String getReason() {return reason;}

    public String toString() {
        String temp = "";
        return temp = getId() + getReason() + getTimeStamp();
    }

}

