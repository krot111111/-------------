package ru.uniride;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class User {
    public String id;
    public String name;
    public String phone;
    public String tg;
    public String vk;

    // Отметка организатора для ЭТОЙ конкретной поездки - можно переключать сколько угодно раз,
    // пока поездка активна. В общий счётчик неявок студента попадает только один раз,
    // при авто-удалении поездки (см. Main.expireOldRides)
    public boolean noShow = false;

    // Сколько неявок числится за этим студентом всего (историческая репутация) -
    // проставляется сервером из Student.noShowCount при создании поездки/вступлении в неё
    public int noShowCount;

    public User() {}
}
