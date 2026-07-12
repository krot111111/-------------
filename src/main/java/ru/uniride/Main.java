package ru.uniride;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Main {
    private static List<Ride> rides = new CopyOnWriteArrayList<>();
    private static Set<WsContext> clients = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) {
        Javalin app = Javalin.create(config -> {
            // Serve static files from the root directory so we don't have to move index.html
            config.staticFiles.add(System.getProperty("user.dir"), Location.EXTERNAL);
        }).start(3001);

        System.out.println("Java Сервер запущен на порту 3000");

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
                            // Серверная проверка создателя вместо клиентского req.isCreator
                            if (target.creator.equals(req.userId)) {
                                rides.remove(target);
                            } else {
                                target.participants.removeIf(p -> p.id.equals(req.userId));
                                target.status = "ACTIVE";
                            }
                            broadcast("updateRides", rides);
                        }
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

    private static void broadcast(String event, Object data) {
        clients.forEach(client -> {
            if (client.session.isOpen()) {
                client.send(new SocketResponse(event, data));
            }
        });
    }
}
