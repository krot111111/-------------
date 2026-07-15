package ru.uniride;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
    private static List<Ride> rides = new CopyOnWriteArrayList<>();
    private static Set<WsContext> clients = ConcurrentHashMap.newKeySet();
    // Токен -> текущее соединение админа. Не привязано к конкретному WsContext,
    // поэтому переподключение сокета (обрыв связи, рестарт сервера) не сбрасывает авторизацию.
    private static Map<String, WsContext> adminSessions = new ConcurrentHashMap<>();

    // Реальные значения задаются переменными окружения ADMIN_USERNAME/ADMIN_PASSWORD -
    // здесь только заглушки, чтобы в публичном репозитории не оказался настоящий пароль
    private static final String ADMIN_USERNAME = System.getenv().getOrDefault("ADMIN_USERNAME", "admin");
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "changeme");

    public static void main(String[] args) {
        Database.initSchema();

        Javalin app = Javalin.create(config -> {
            // Serve static files from the root directory so we don't have to move index.html
            config.staticFiles.add(System.getProperty("user.dir"), Location.EXTERNAL);
        }).start(3001);

        System.out.println("Java Сервер запущен на порту 3000");

        // Раз в минуту чистим поездки, у которых с назначенного времени прошло больше 40 минут
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ride-expiry");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(Main::expireOldRides, 1, 1, TimeUnit.MINUTES);

        app.ws("/socket", ws -> {
            ws.onConnect(ctx -> {
                clients.add(ctx);
                ctx.send(new SocketResponse("updateRides", rides));
                System.out.println("Клиент подключился: " + ctx.getSessionId());
            });

            ws.onMessage(ctx -> {
                try {
                    SocketRequest req = ctx.messageAsClass(SocketRequest.class);

                    if ("createRide".equals(req.type)) {
                        if (req.rideData == null || !req.rideData.isValid()) {
                            ctx.send(new SocketResponse("errorMsg", "Ошибка сервера: переданы пустые или некорректные данные поездки"));
                            return;
                        }
                        if (isUserInActiveRide(req.rideData.creator)) {
                            ctx.send(new SocketResponse("errorMsg", "Ошибка: у вас уже есть активная поездка"));
                            return;
                        }
                        req.rideData.scheduledAt = resolveScheduledAt(req.rideData.time);
                        rides.add(req.rideData);
                        System.out.println("Создана новая поездка: " + req.rideData.id);
                        broadcast("updateRides", rides);
                    } 
                    else if ("joinRide".equals(req.type)) {
                        if (req.user == null || req.user.id == null) {
                            ctx.send(new SocketResponse("errorMsg", "Ошибка: некорректные данные пользователя"));
                            return;
                        }
                        if (isUserInActiveRide(req.user.id)) {
                            ctx.send(new SocketResponse("errorMsg", "Ошибка: вы уже состоите в другой поездке"));
                            return;
                        }

                        Ride target = rides.stream()
                            .filter(r -> r.id.equals(req.rideId))
                            .findFirst()
                            .orElse(null);

                        if (target == null) {
                            ctx.send(new SocketResponse("errorMsg", "Поездка не найдена"));
                            return;
                        }
                        if (target.participants.stream().anyMatch(p -> p.id.equals(req.user.id))) {
                            ctx.send(new SocketResponse("errorMsg", "Вы уже состоите в этой поездке"));
                            return;
                        }
                        if (target.participants.size() > target.totalSeats) {
                            ctx.send(new SocketResponse("errorMsg", "Мест нет"));
                            return;
                        }

                        target.participants.add(req.user);
                        if (target.participants.size() > target.totalSeats) {
                            target.status = "FULL";
                        }
                        broadcast("updateRides", rides);
                    }
                    else if ("leaveRide".equals(req.type)) {
                        Ride target = rides.stream()
                            .filter(r -> r.id.equals(req.rideId))
                            .findFirst()
                            .orElse(null);
                        
                        if (target != null) {
                            // Кто создатель, определяем сами - клиенту не доверяем
                            if (target.creator.equals(req.userId)) {
                                rides.remove(target);
                            } else {
                                target.participants.removeIf(p -> p.id.equals(req.userId));
                                target.status = "ACTIVE";
                            }
                            broadcast("updateRides", rides);
                        }
                    }
                    else if ("postponeRide".equals(req.type)) {
                        Ride target = rides.stream()
                            .filter(r -> r.id.equals(req.rideId))
                            .findFirst()
                            .orElse(null);

                        if (target == null) {
                            ctx.send(new SocketResponse("errorMsg", "Поездка не найдена"));
                            return;
                        }
                        if (!target.creator.equals(req.userId)) {
                            ctx.send(new SocketResponse("errorMsg", "Только организатор может перенести время поездки"));
                            return;
                        }
                        // Переносим настоящую дату-время (а не только строку) - так перенос
                        // через полночь не ломает расчёт "прошло 40 минут" для авто-удаления
                        target.scheduledAt = target.scheduledAt.plusMinutes(5);
                        target.time = target.scheduledAt.toLocalTime().toString();
                        broadcast("updateRides", rides);
                    }
                    else if ("registerStudent".equals(req.type)) {
                        String firstName = req.firstName == null ? "" : req.firstName.trim();
                        String lastName = req.lastName == null ? "" : req.lastName.trim();
                        String groupNumber = req.groupNumber == null ? "" : req.groupNumber.trim();
                        String phoneNumber = req.phoneNumber == null ? "" : req.phoneNumber.trim();
                        String gradebookNumber = req.gradebookNumber == null ? "" : req.gradebookNumber.trim();
                        String password = req.password == null ? "" : req.password.trim();

                        if (firstName.isEmpty() || lastName.isEmpty() || groupNumber.isEmpty()
                                || phoneNumber.isEmpty() || gradebookNumber.isEmpty()
                                || firstName.length() > 100 || lastName.length() > 100 || groupNumber.length() > 50
                                || phoneNumber.length() > 20 || gradebookNumber.length() > 50) {
                            ctx.send(new SocketResponse("errorMsg", "Заполните имя, фамилию, группу, телефон и номер зачётки корректно"));
                            return;
                        }
                        if (password.length() < 4 || password.length() > 100) {
                            ctx.send(new SocketResponse("errorMsg", "Пароль должен быть от 4 до 100 символов"));
                            return;
                        }

                        AuthSession session;
                        try {
                            session = StudentRepository.register(firstName, lastName, groupNumber, phoneNumber, gradebookNumber, password);
                        } catch (SQLException e) {
                            if ("23505".equals(e.getSQLState())) {
                                ctx.send(new SocketResponse("errorMsg", "Этот номер телефона или зачётки уже используется"));
                                return;
                            }
                            throw e;
                        }
                        ctx.send(new SocketResponse("registrationResult", session));
                        notifyAdmins();
                        System.out.println("Новая заявка на регистрацию: " + session.student.id);
                    }
                    else if ("studentLogin".equals(req.type)) {
                        String gradebookNumber = req.gradebookNumber == null ? "" : req.gradebookNumber.trim();
                        String password = req.password == null ? "" : req.password.trim();

                        if (gradebookNumber.isEmpty() || password.isEmpty()) {
                            ctx.send(new SocketResponse("errorMsg", "Введите номер зачётки и пароль"));
                            return;
                        }

                        AuthSession session = StudentRepository.verifyLogin(gradebookNumber, password);
                        if (session == null) {
                            ctx.send(new SocketResponse("errorMsg", "Неверный номер зачётки или пароль"));
                            return;
                        }
                        ctx.send(new SocketResponse("loginResult", session));
                    }
                    else if ("checkStatus".equals(req.type)) {
                        if (req.studentToken == null || req.studentToken.isEmpty()) {
                            ctx.send(new SocketResponse("sessionInvalid", null));
                            return;
                        }
                        Student student = StudentRepository.findBySessionToken(req.studentToken);
                        if (student == null) {
                            ctx.send(new SocketResponse("sessionInvalid", null));
                            return;
                        }
                        ctx.send(new SocketResponse("statusResult", student));
                    }
                    else if ("adminLogin".equals(req.type)) {
                        boolean success = ADMIN_USERNAME.equals(req.adminUsername) && ADMIN_PASSWORD.equals(req.adminPassword);
                        if (success) {
                            String token = UUID.randomUUID().toString();
                            adminSessions.put(token, ctx);
                            ctx.send(new SocketResponse("adminLoginResult", new AdminLoginResult(true, null, token)));
                        } else {
                            ctx.send(new SocketResponse("adminLoginResult", new AdminLoginResult(false, "Неверный логин или пароль", null)));
                        }
                    }
                    else if ("adminLogout".equals(req.type)) {
                        if (req.adminToken != null) adminSessions.remove(req.adminToken);
                    }
                    else if ("getAllUsers".equals(req.type)) {
                        if (!authorizeAdmin(ctx, req.adminToken)) {
                            ctx.send(new SocketResponse("adminAuthRequired", null));
                            return;
                        }
                        ctx.send(new SocketResponse("allUsersList", StudentRepository.findAll()));
                    }
                    else if ("approveStudent".equals(req.type)) {
                        if (!authorizeAdmin(ctx, req.adminToken)) {
                            ctx.send(new SocketResponse("adminAuthRequired", null));
                            return;
                        }
                        if (req.studentId == null) {
                            ctx.send(new SocketResponse("errorMsg", "Не указан идентификатор пользователя"));
                            return;
                        }
                        StudentRepository.updateStatus(req.studentId, "APPROVED");
                        ctx.send(new SocketResponse("allUsersList", StudentRepository.findAll()));
                    }
                    // Отказ в заявке и отзыв доступа - в обоих случаях запись целиком удаляется из базы,
                    // а не зависает в статусе REJECTED. Это же освобождает телефон и зачётку для новой регистрации.
                    else if ("rejectStudent".equals(req.type) || "revokeAccess".equals(req.type)) {
                        if (!authorizeAdmin(ctx, req.adminToken)) {
                            ctx.send(new SocketResponse("adminAuthRequired", null));
                            return;
                        }
                        if (req.studentId == null) {
                            ctx.send(new SocketResponse("errorMsg", "Не указан идентификатор пользователя"));
                            return;
                        }
                        StudentRepository.deleteStudent(req.studentId);
                        removeUserFromAllRides("student-" + req.studentId);
                        ctx.send(new SocketResponse("allUsersList", StudentRepository.findAll()));
                    }
                } catch (Exception e) {
                    System.err.println("Ошибка обработки WebSocket сообщения: " + e.getMessage());
                    e.printStackTrace();
                }
            });

            ws.onClose(ctx -> {
                clients.remove(ctx);
                System.out.println("Клиент отключился: " + ctx.getSessionId());
            });
        });
    }

    private static boolean isUserInActiveRide(String userId) {
        return rides.stream().anyMatch(r ->
            ("ACTIVE".equals(r.status) || "FULL".equals(r.status)) &&
            r.participants.stream().anyMatch(p -> p.id.equals(userId))
        );
    }

    // Поездка хранит время только как "ЧЧ:мм" без даты. Если это время уже прошло больше
    // часа назад - считаем, что имелось в виду завтра (та же эвристика, что и в таймере на клиенте).
    private static LocalDateTime resolveScheduledAt(String timeStr) {
        LocalTime time = LocalTime.parse(timeStr);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime scheduled = now.withHour(time.getHour()).withMinute(time.getMinute()).withSecond(0).withNano(0);
        if (scheduled.isBefore(now.minusHours(1))) {
            scheduled = scheduled.plusDays(1);
        }
        return scheduled;
    }

    // Удаляет поездки, с назначенного времени которых прошло больше 40 минут
    private static void expireOldRides() {
        LocalDateTime now = LocalDateTime.now();
        boolean changed = rides.removeIf(r -> r.scheduledAt != null && now.isAfter(r.scheduledAt.plusMinutes(40)));
        if (changed) {
            System.out.println("Удалены просроченные поездки (прошло больше 40 минут после начала)");
            broadcast("updateRides", rides);
        }
    }

    // Вызывается при отказе/отзыве доступа - если пользователь был организатором поездки,
    // поездка отменяется целиком; если просто участником - убираем его из списка
    private static void removeUserFromAllRides(String userId) {
        List<Ride> toRemove = new ArrayList<>();
        boolean changed = false;
        for (Ride ride : rides) {
            if (userId.equals(ride.creator)) {
                toRemove.add(ride);
                changed = true;
            } else if (ride.participants.removeIf(p -> userId.equals(p.id))) {
                ride.status = "ACTIVE";
                changed = true;
            }
        }
        rides.removeAll(toRemove);
        if (changed) {
            broadcast("updateRides", rides);
        }
    }

    private static void broadcast(String event, Object data) {
        clients.forEach(client -> {
            if (client.session.isOpen()) {
                client.send(new SocketResponse(event, data));
            }
        });
    }

    private static void notifyAdmins() {
        try {
            List<Student> allUsers = StudentRepository.findAll();
            adminSessions.values().forEach(adminCtx -> {
                if (adminCtx.session.isOpen()) {
                    adminCtx.send(new SocketResponse("allUsersList", allUsers));
                }
            });
        } catch (Exception e) {
            System.err.println("Не удалось уведомить администраторов: " + e.getMessage());
        }
    }

    // Проверяет токен и обновляет привязанное к нему соединение,
    // чтобы push-уведомления доходили даже после переподключения сокета
    private static boolean authorizeAdmin(WsContext ctx, String token) {
        if (token == null || !adminSessions.containsKey(token)) return false;
        adminSessions.put(token, ctx);
        return true;
    }
}
