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

    // Соединение -> id студента, который на том конце. Нужно, чтобы решать, кому
    // показывать реальные контакты/координаты поездки, а кому - урезанную версию.
    private static Map<WsContext, Long> ctxStudent = new ConcurrentHashMap<>();

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
                // Личность ещё не известна (клиент пришлёт identify следующим сообщением) -
                // до этого момента отдаём версию без чужих контактов и координат
                ctx.send(new SocketResponse("updateRides", visibleRidesFor(null)));
                System.out.println("Клиент подключился: " + ctx.getSessionId());
            });

            ws.onMessage(ctx -> {
                try {
                    SocketRequest req = ctx.messageAsClass(SocketRequest.class);

                    if ("identify".equals(req.type)) {
                        // Клиент называет себя токеном сразу после подключения - без этого
                        // сервер не знает, кому можно показывать реальные контакты/координаты
                        if (requireStudent(ctx, req.studentToken) != null) {
                            ctx.send(new SocketResponse("updateRides", visibleRidesFor(ctxStudent.get(ctx))));
                        }
                    }
                    else if ("createRide".equals(req.type)) {
                        Student student = requireStudent(ctx, req.studentToken);
                        if (student == null) {
                            ctx.send(new SocketResponse("errorMsg", "Требуется авторизация"));
                            return;
                        }
                        String myId = "student-" + student.id;

                        if (req.rideData == null) {
                            ctx.send(new SocketResponse("errorMsg", "Ошибка сервера: переданы пустые данные поездки"));
                            return;
                        }
                        // Личность создателя и первого участника берём из токена, а не из тела запроса -
                        // иначе можно было бы создать поездку от чужого имени
                        req.rideData.creator = myId;
                        User creatorParticipant;
                        if (req.rideData.participants != null && !req.rideData.participants.isEmpty()) {
                            creatorParticipant = req.rideData.participants.get(0);
                        } else {
                            creatorParticipant = new User();
                            req.rideData.participants = new ArrayList<>();
                            req.rideData.participants.add(creatorParticipant);
                        }
                        creatorParticipant.id = myId;
                        creatorParticipant.name = student.firstName + " " + student.lastName;
                        creatorParticipant.noShowCount = student.noShowCount;

                        if (!req.rideData.isValid()) {
                            ctx.send(new SocketResponse("errorMsg", "Ошибка сервера: переданы некорректные данные поездки"));
                            return;
                        }
                        if (isUserInActiveRide(myId)) {
                            ctx.send(new SocketResponse("errorMsg", "Ошибка: у вас уже есть активная поездка"));
                            return;
                        }
                        req.rideData.scheduledAt = resolveScheduledAt(req.rideData.time);
                        rides.add(req.rideData);
                        System.out.println("Создана новая поездка: " + req.rideData.id);
                        broadcastRides();
                    }
                    else if ("joinRide".equals(req.type)) {
                        Student student = requireStudent(ctx, req.studentToken);
                        if (student == null) {
                            ctx.send(new SocketResponse("errorMsg", "Требуется авторизация"));
                            return;
                        }
                        String myId = "student-" + student.id;

                        if (req.user == null) {
                            ctx.send(new SocketResponse("errorMsg", "Ошибка: некорректные данные пользователя"));
                            return;
                        }
                        // Личность - строго из токена; телефон/tg/vk берём из запроса (это личные
                        // контакты профиля, сервер их отдельно не хранит)
                        req.user.id = myId;
                        req.user.name = student.firstName + " " + student.lastName;
                        req.user.noShowCount = student.noShowCount;

                        if (isUserInActiveRide(myId)) {
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
                        if (target.scheduledAt != null && LocalDateTime.now().isAfter(target.scheduledAt)) {
                            ctx.send(new SocketResponse("errorMsg", "Поездка уже началась, вступить нельзя"));
                            return;
                        }
                        if (target.participants.stream().anyMatch(p -> p.id.equals(myId))) {
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
                        broadcastRides();
                    }
                    else if ("leaveRide".equals(req.type)) {
                        Student student = requireStudent(ctx, req.studentToken);
                        if (student == null) {
                            ctx.send(new SocketResponse("errorMsg", "Требуется авторизация"));
                            return;
                        }
                        String myId = "student-" + student.id;

                        Ride target = rides.stream()
                            .filter(r -> r.id.equals(req.rideId))
                            .findFirst()
                            .orElse(null);

                        if (target != null) {
                            // Кто создатель, определяем сами - клиенту не доверяем
                            if (target.creator.equals(myId)) {
                                rides.remove(target);
                            } else {
                                target.participants.removeIf(p -> p.id.equals(myId));
                                target.status = "ACTIVE";
                            }
                            broadcastRides();
                        }
                    }
                    else if ("postponeRide".equals(req.type)) {
                        Student student = requireStudent(ctx, req.studentToken);
                        if (student == null) {
                            ctx.send(new SocketResponse("errorMsg", "Требуется авторизация"));
                            return;
                        }
                        String myId = "student-" + student.id;

                        Ride target = rides.stream()
                            .filter(r -> r.id.equals(req.rideId))
                            .findFirst()
                            .orElse(null);

                        if (target == null) {
                            ctx.send(new SocketResponse("errorMsg", "Поездка не найдена"));
                            return;
                        }
                        if (!target.creator.equals(myId)) {
                            ctx.send(new SocketResponse("errorMsg", "Только организатор может перенести время поездки"));
                            return;
                        }
                        // Переносим настоящую дату-время (а не только строку) - так перенос
                        // через полночь не ломает расчёт "прошло 40 минут" для авто-удаления
                        target.scheduledAt = target.scheduledAt.plusMinutes(5);
                        target.time = target.scheduledAt.toLocalTime().toString();
                        broadcastRides();
                    }
                    // Отметка "пришёл/не пришёл" - можно переключать сколько угодно раз, пока поездка
                    // активна. Организатор отмечает попутчиков, а попутчики могут отметить организатора.
                    // В счётчик неявок студента это попадает только один раз, при авто-удалении
                    // поездки (expireOldRides) - здесь только локальное состояние конкретной поездки.
                    else if ("markAttendance".equals(req.type)) {
                        Student student = requireStudent(ctx, req.studentToken);
                        if (student == null) {
                            ctx.send(new SocketResponse("errorMsg", "Требуется авторизация"));
                            return;
                        }
                        String myId = "student-" + student.id;

                        Ride target = rides.stream()
                            .filter(r -> r.id.equals(req.rideId))
                            .findFirst()
                            .orElse(null);

                        if (target == null) {
                            ctx.send(new SocketResponse("errorMsg", "Поездка не найдена"));
                            return;
                        }
                        String validationError = validateAttendanceMark(target, myId, req.studentId, req.attended, LocalDateTime.now());
                        if (validationError != null) {
                            ctx.send(new SocketResponse("errorMsg", validationError));
                            return;
                        }

                        String targetId = studentUserId(req.studentId);
                        User participant = target.participants.stream()
                            .filter(p -> p.id.equals(targetId))
                            .findFirst()
                            .orElse(null);
                        if (participant == null) {
                            ctx.send(new SocketResponse("errorMsg", "Участник не найден"));
                            return;
                        }
                        participant.noShow = !req.attended;
                        broadcastRides();
                    }
                    // Убрать одного конкретного участника, не отменяя поездку целиком -
                    // доступно организатору в любой момент, не связано с отметкой неявки
                    else if ("kickParticipant".equals(req.type)) {
                        Student student = requireStudent(ctx, req.studentToken);
                        if (student == null) {
                            ctx.send(new SocketResponse("errorMsg", "Требуется авторизация"));
                            return;
                        }
                        String myId = "student-" + student.id;

                        Ride target = rides.stream()
                            .filter(r -> r.id.equals(req.rideId))
                            .findFirst()
                            .orElse(null);

                        if (target == null) {
                            ctx.send(new SocketResponse("errorMsg", "Поездка не найдена"));
                            return;
                        }
                        if (!target.creator.equals(myId)) {
                            ctx.send(new SocketResponse("errorMsg", "Только организатор может убрать участника"));
                            return;
                        }
                        if (req.studentId == null) {
                            ctx.send(new SocketResponse("errorMsg", "Не указан участник"));
                            return;
                        }
                        String targetId = "student-" + req.studentId;
                        if (targetId.equals(myId)) {
                            ctx.send(new SocketResponse("errorMsg", "Нельзя убрать самого себя - отмените поездку целиком"));
                            return;
                        }
                        if (target.participants.removeIf(p -> p.id.equals(targetId))) {
                            target.status = "ACTIVE";
                            broadcastRides();
                        }
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
                        ctxStudent.put(ctx, session.student.id);
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
                        ctxStudent.put(ctx, session.student.id);
                        ctx.send(new SocketResponse("loginResult", session));
                    }
                    else if ("checkStatus".equals(req.type)) {
                        Student student = requireStudent(ctx, req.studentToken);
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
                ctxStudent.remove(ctx);
                System.out.println("Клиент отключился: " + ctx.getSessionId());
            });
        });
    }

    private static boolean isUserInActiveRide(String userId) {
        return isUserInActiveRide(rides, userId);
    }

    static boolean isUserInActiveRide(List<Ride> source, String userId) {
        return source.stream().anyMatch(r ->
            ("ACTIVE".equals(r.status) || "FULL".equals(r.status)) &&
            r.participants.stream().anyMatch(p -> p.id.equals(userId))
        );
    }

    // Поездка хранит время только как "ЧЧ:мм" без даты. Если это время уже прошло больше
    // часа назад - считаем, что имелось в виду завтра (та же эвристика, что и в таймере на клиенте).
    private static LocalDateTime resolveScheduledAt(String timeStr) {
        return resolveScheduledAt(timeStr, LocalDateTime.now());
    }

    static LocalDateTime resolveScheduledAt(String timeStr, LocalDateTime now) {
        LocalTime time = LocalTime.parse(timeStr);
        LocalDateTime scheduled = now.withHour(time.getHour()).withMinute(time.getMinute()).withSecond(0).withNano(0);
        if (scheduled.isBefore(now.minusHours(1))) {
            scheduled = scheduled.plusDays(1);
        }
        return scheduled;
    }

    // Удаляет поездки, с назначенного времени которых прошло больше 40 минут.
    // Перед удалением один раз применяет отметки "не пришёл" к счётчику неявок студента -
    // это единственное место, где счётчик реально меняется (переключения организатора
    // по ходу поездки в него не пишут, только финальный снимок на момент удаления).
    private static void expireOldRides() {
        LocalDateTime now = LocalDateTime.now();
        List<Ride> toExpire = new ArrayList<>();
        for (Ride r : rides) {
            if (shouldExpireRide(r, now)) {
                toExpire.add(r);
            }
        }
        if (toExpire.isEmpty()) return;

        for (Ride ride : toExpire) {
            for (User p : ride.participants) {
                if (p.noShow) {
                    Long id = parseStudentId(p.id);
                    if (id != null) {
                        try {
                            StudentRepository.incrementNoShow(id);
                        } catch (SQLException e) {
                            System.err.println("Не удалось обновить счётчик неявок: " + e.getMessage());
                        }
                    }
                }
            }
        }
        rides.removeAll(toExpire);
        System.out.println("Удалены просроченные поездки (прошло больше 40 минут после начала)");
        broadcastRides();
    }

    // "student-7" -> 7L; null, если строка не соответствует ожидаемому формату
    static boolean shouldExpireRide(Ride ride, LocalDateTime now) {
        return ride.scheduledAt != null && now.isAfter(ride.scheduledAt.plusMinutes(40));
    }

    static Long parseStudentId(String userId) {
        if (userId == null || !userId.startsWith("student-")) return null;
        try {
            return Long.parseLong(userId.substring("student-".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Вызывается при отказе/отзыве доступа - если пользователь был организатором поездки,
    // поездка отменяется целиком; если просто участником - убираем его из списка
    private static void removeUserFromAllRides(String userId) {
        boolean changed = removeUserFromRides(rides, userId);
        if (changed) {
            broadcastRides();
        }
    }

    static boolean removeUserFromRides(List<Ride> source, String userId) {
        List<Ride> toRemove = new ArrayList<>();
        boolean changed = false;
        for (Ride ride : source) {
            if (userId.equals(ride.creator)) {
                toRemove.add(ride);
                changed = true;
            } else if (ride.participants.removeIf(p -> userId.equals(p.id))) {
                ride.status = "ACTIVE";
                changed = true;
            }
        }
        source.removeAll(toRemove);
        return changed;
    }

    // Каждому подключению рассылается своя версия списка: в чужих поездках
    // (где получатель не участник) контакты и точка сбора обнулены
    private static void broadcastRides() {
        clients.forEach(client -> {
            if (client.session.isOpen()) {
                client.send(new SocketResponse("updateRides", visibleRidesFor(ctxStudent.get(client))));
            }
        });
    }

    // Возвращает список поездок для конкретного студента: в его собственных поездках -
    // полные данные, в остальных - без контактов участников и без координат точки сбора
    private static List<Ride> visibleRidesFor(Long studentId) {
        return visibleRidesFor(rides, studentId);
    }

    static List<Ride> visibleRidesFor(List<Ride> source, Long studentId) {
        String myId = studentId == null ? null : "student-" + studentId;
        List<Ride> result = new ArrayList<>();
        for (Ride r : source) {
            boolean isMine = myId != null && (myId.equals(r.creator) || r.participants.stream().anyMatch(p -> myId.equals(p.id)));
            result.add(isMine ? r : redacted(r));
        }
        return result;
    }

    // Копия поездки только с полями, безопасными для показа не-участнику
    static Ride redacted(Ride original) {
        Ride copy = new Ride();
        copy.id = original.id;
        copy.creator = original.creator;
        copy.departure = original.departure;
        copy.destination = original.destination;
        copy.time = original.time;
        copy.totalSeats = original.totalSeats;
        copy.status = original.status;
        // lat/lon намеренно не копируем - точку сбора видят только участники
        for (User p : original.participants) {
            User redactedUser = new User();
            redactedUser.id = p.id;
            redactedUser.name = p.name;
            redactedUser.noShowCount = p.noShowCount; // не секрет - общая репутация видна всем
            // phone/tg/vk намеренно не копируем - контакты видят только участники
            copy.participants.add(redactedUser);
        }
        return copy;
    }

    static String studentUserId(long studentId) {
        return "student-" + studentId;
    }

    static String validateAttendanceMark(Ride target, String requesterId, Long targetStudentId,
                                         Boolean attended, LocalDateTime now) {
        if (target == null) {
            return "Поездка не найдена";
        }
        if (targetStudentId == null || attended == null) {
            return "Не указан участник или отметка";
        }
        if (target.scheduledAt != null && now.isBefore(target.scheduledAt)) {
            return "Явку можно отмечать только после начала поездки";
        }

        String targetId = studentUserId(targetStudentId);
        boolean requesterIsCreator = requesterId != null && requesterId.equals(target.creator);
        boolean targetIsCreator = targetId.equals(target.creator);
        boolean requesterIsParticipant = target.participants.stream().anyMatch(p -> requesterId != null && requesterId.equals(p.id));

        if (targetIsCreator) {
            if (requesterIsCreator || !requesterIsParticipant) {
                return "Организатора могут отмечать только попутчики";
            }
        } else if (!requesterIsCreator) {
            return "Только организатор может отмечать явку участников";
        }

        boolean targetIsParticipant = target.participants.stream().anyMatch(p -> targetId.equals(p.id));
        if (!targetIsParticipant) {
            return "Участник не найден";
        }
        return null;
    }

    // Находит студента по токену сессии и запоминает, какое соединение ему принадлежит -
    // это единственный источник личности для действий с поездками (клиенту не доверяем)
    private static Student requireStudent(WsContext ctx, String token) throws SQLException {
        if (token == null || token.isEmpty()) return null;
        Student student = StudentRepository.findBySessionToken(token);
        if (student != null) {
            ctxStudent.put(ctx, student.id);
        }
        return student;
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
