/**
 * UniRide - Frontend Logic Prototype
 * This acts as a mockup for the future Java + PostgreSQL backend.
 */

// Application State
const state = {
    currentUser: null, // Loaded from localStorage (approved student, profile filled in)
    pendingStudent: null, // Loaded from localStorage (registration awaiting/refused moderation)
    currentView: 'view-register', // Изменено стартовое окно на регистрацию
    activeRideId: null,
    rides: [], // Fetched from server
    allUsers: [] // Все зарегистрированные пользователи, приходят от сервера (только для админа)
};

// Native WebSocket Wrapper
const wsUrl = window.location.protocol === 'https:' ? `wss://${window.location.host}/socket` : `ws://${window.location.host}/socket`;
let ws;

const socket = {
    callbacks: {},
    messageQueue: [],
    on(event, cb) {
        this.callbacks[event] = cb;
    },
    connect() {
        ws = new WebSocket(wsUrl);
        ws.onmessage = (event) => {
            const res = JSON.parse(event.data);
            if (this.callbacks[res.type]) {
                this.callbacks[res.type](res.data);
            }
        };
        ws.onopen = () => {
            while (this.messageQueue.length > 0) {
                ws.send(this.messageQueue.shift());
            }
        };
        ws.onclose = () => {
            setTimeout(() => this.connect(), 1000);
        };
    },
    emit(event, data) {
        let msg = { type: event };
        if (event === 'createRide') msg.rideData = data;
        else if (event === 'joinRide') { msg.rideId = data.rideId; msg.user = data.user; }
        else if (event === 'leaveRide' || event === 'postponeRide') { msg.rideId = data.rideId; msg.userId = data.userId; }
        else if (event === 'registerStudent') { msg.firstName = data.firstName; msg.lastName = data.lastName; msg.groupNumber = data.groupNumber; msg.phoneNumber = data.phoneNumber; msg.gradebookNumber = data.gradebookNumber; msg.password = data.password; }
        else if (event === 'studentLogin') { msg.gradebookNumber = data.gradebookNumber; msg.password = data.password; }
        else if (event === 'checkStatus') { msg.studentToken = data.studentToken; }
        else if (event === 'adminLogin') { msg.adminUsername = data.adminUsername; msg.adminPassword = data.adminPassword; }
        else if (event === 'approveStudent' || event === 'rejectStudent' || event === 'revokeAccess') { msg.studentId = data.studentId; }
        else msg.data = data; // Для новых событий без дополнительных полей (getAllUsers, adminLogout)

        // Токен админа привязывается к каждому админскому запросу отдельно,
        // чтобы авторизация переживала переподключение сокета
        if (['getAllUsers', 'approveStudent', 'rejectStudent', 'revokeAccess', 'adminLogout'].includes(event)) {
            msg.adminToken = localStorage.getItem('uniride_admin_token');
        }

        const payload = JSON.stringify(msg);
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(payload);
        } else {
            this.messageQueue.push(payload);
        }
    }
};

socket.connect();

// Listen to server events
socket.on('updateRides', (ridesData) => {
    state.rides = ridesData;
    if (state.currentView === 'view-dashboard') {
        app.renderDashboard();
    } else if (state.currentView === 'view-ride-details') {
        app.renderRideDetails();
    }
});

socket.on('errorMsg', (msg) => {
    app.showToast(msg);
});

socket.on('registrationResult', (session) => {
    state.pendingStudent = { ...session.student, sessionToken: session.sessionToken };
    localStorage.setItem('uniride_pending', JSON.stringify(state.pendingStudent));
    app.showToast('Заявка отправлена! Ожидайте подтверждения старосты группы.');
    app.navigate('pending');
});

socket.on('statusResult', (student) => {
    app.handleStatusUpdate(student);
});

socket.on('loginResult', (session) => {
    app.handleLoginResult(session);
});

socket.on('sessionInvalid', () => {
    app.stopStatusPolling();
    app.stopSessionCheck();
    if (state.currentUser) {
        localStorage.removeItem('uniride_user');
        state.currentUser = null;
        app.showToast('Доступ отозван администратором');
    } else if (state.pendingStudent) {
        localStorage.removeItem('uniride_pending');
        state.pendingStudent = null;
        app.showToast('Заявка отклонена администратором');
    }
    app.navigate('register');
});

socket.on('adminLoginResult', (result) => {
    if (result.success) {
        document.getElementById('admin-pass').value = '';
        localStorage.setItem('uniride_admin_token', result.token);
        app.navigate('admin-dashboard');
    } else {
        app.showToast(result.message || 'Неверный логин или пароль!');
    }
});

socket.on('adminAuthRequired', () => {
    localStorage.removeItem('uniride_admin_token');
    app.showToast('Сессия администратора истекла, войдите заново');
    app.navigate('admin-login');
});

socket.on('allUsersList', (list) => {
    state.allUsers = list;
    if (state.currentView === 'view-admin-dashboard') {
        app.renderUsersList();
    }
});

const app = {
    createMapInstance: null,
    createMarker: null,
    currentLat: null,
    currentLon: null,
    detailsMapInstance: null,
    detailsMarker: null,

    init() {
        this.bindAuthEvents(); // Привязываем события для новых окон авторизации

        const savedUser = localStorage.getItem('uniride_user');
        const savedPending = localStorage.getItem('uniride_pending');
        if (savedUser) {
            state.currentUser = JSON.parse(savedUser);
            this.navigate('dashboard');
            this.startSessionCheck();
        } else if (savedPending) {
            state.pendingStudent = JSON.parse(savedPending);
            this.navigate('pending');
        } else {
            this.navigate('register');
        }
        
        // Preset time input
        const now = new Date();
        now.setMinutes(now.getMinutes() + 15);
        document.getElementById('create-time').value = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
    },

    bindAuthEvents() {
        // Навигация между окнами авторизации
        document.getElementById('link-admin-login')?.addEventListener('click', (e) => {
            e.preventDefault();
            this.navigate('admin-login');
        });

        document.getElementById('link-register')?.addEventListener('click', (e) => {
            e.preventDefault();
            this.navigate('register');
        });

        document.getElementById('link-goto-login')?.addEventListener('click', (e) => {
            e.preventDefault();
            this.navigate('login');
        });

        document.getElementById('link-goto-register')?.addEventListener('click', (e) => {
            e.preventDefault();
            this.navigate('register');
        });

        document.getElementById('btn-logout')?.addEventListener('click', () => {
            socket.emit('adminLogout', {});
            localStorage.removeItem('uniride_admin_token');
            this.navigate('admin-login');
        });

        // Регистрация студента
        document.getElementById('register-form')?.addEventListener('submit', (e) => {
            e.preventDefault();
            const firstName = document.getElementById('reg-name').value.trim();
            const lastName = document.getElementById('reg-surname').value.trim();
            const groupNumber = document.getElementById('reg-group').value.trim();
            const phoneNumber = document.getElementById('reg-phone').value.trim();
            const gradebookNumber = document.getElementById('reg-gradebook').value.trim();
            const password = document.getElementById('reg-password').value;

            socket.emit('registerStudent', { firstName, lastName, groupNumber, phoneNumber, gradebookNumber, password });
            e.target.reset();
        });

        // Вход студента (по номеру зачётки и паролю)
        document.getElementById('login-form')?.addEventListener('submit', (e) => {
            e.preventDefault();
            const gradebookNumber = document.getElementById('login-gradebook').value.trim();
            const password = document.getElementById('login-password').value;

            socket.emit('studentLogin', { gradebookNumber, password });
        });

        // Вход администратора (логин/пароль проверяются на сервере)
        document.getElementById('admin-login-form')?.addEventListener('submit', (e) => {
            e.preventDefault();
            const adminUsername = document.getElementById('admin-user').value;
            const adminPassword = document.getElementById('admin-pass').value;

            socket.emit('adminLogin', { adminUsername, adminPassword });
        });
    },

    // UI Navigation & Utilities
    navigate(viewId) {
        if (!viewId.startsWith('view-')) viewId = 'view-' + viewId;
        
        // Hide all views
        document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
        // Show target
        const target = document.getElementById(viewId);
        if (target) target.classList.add('active');
        
        state.currentView = viewId;
        window.scrollTo(0, 0);

        // Update Navbar visibility
        const nav = document.getElementById('main-nav');
        if (['view-register', 'view-login', 'view-admin-login', 'view-admin-dashboard', 'view-pending'].includes(viewId)) {
            if(nav) nav.style.display = 'none';
        } else {
            if(nav) nav.style.display = 'block';
        }

        // Опрос статуса заявки идет только пока пользователь находится на экране ожидания
        if (viewId === 'view-pending') {
            this.checkPendingStatus();
            this.startStatusPolling();
        } else {
            this.stopStatusPolling();
        }

        // Таймер до отправления тикает только пока виден дашборд
        if (viewId !== 'view-dashboard') {
            this.stopRideCountdown();
        }

        // View specific logic
        if (viewId === 'view-dashboard') this.renderDashboard();
        if (viewId === 'view-profile') this.populateProfileForm();
        if (viewId === 'view-ride-details') this.renderRideDetails();
        if (viewId === 'view-admin-dashboard') this.renderAdminDashboard();
    },

    showToast(message) {
        const toast = document.getElementById('toast');
        toast.textContent = message;
        toast.classList.add('show');
        setTimeout(() => toast.classList.remove('show'), 3000);
    },

    showModal(modalId) {
        document.getElementById(modalId).classList.add('active');
        if (modalId === 'create-ride-modal') {
            setTimeout(() => this.initCreateMap(), 300);
        }
    },

    closeModal(modalId) {
        document.getElementById(modalId).classList.remove('active');
    },

    toggleSelect(id) {
        document.querySelectorAll('.custom-select').forEach(el => {
            if (el.id !== 'custom-' + id) el.classList.remove('open');
        });
        document.getElementById('custom-' + id).classList.toggle('open');
    },

    selectOption(id, value, label = value) {
        document.getElementById('create-' + id).value = value;
        
        const textSpan = document.getElementById(id + '-text');
        textSpan.textContent = label;
        textSpan.classList.remove('text-muted');

        document.getElementById('custom-' + id).classList.remove('open');
    },

    openCombo(id) {
        const d1 = document.getElementById('options-departure');
        const d2 = document.getElementById('options-destination');
        if (d1 && id !== 'departure') d1.style.display = 'none';
        if (d2 && id !== 'destination') d2.style.display = 'none';
        
        document.getElementById('options-' + id).style.display = 'block';
    },

    selectCombo(id, text) {
        document.getElementById('create-' + id).value = text;
        document.getElementById('options-' + id).style.display = 'none';
    },

    initCreateMap() {
        if (!window.ymaps) return;
        ymaps.ready(() => {
            if (this.createMapInstance) {
                if (this.createMarker) this.createMapInstance.geoObjects.remove(this.createMarker);
                this.createMarker = null;
                this.currentLat = null;
                this.currentLon = null;
                return;
            }
            this.createMapInstance = new ymaps.Map('create-map', {
                center: [56.307, 43.996], // пл. Лядова, Нижний Новгород
                zoom: 14,
                controls: ['zoomControl']
            });
            this.createMapInstance.events.add('click', (e) => {
                const coords = e.get('coords');
                this.currentLat = coords[0];
                this.currentLon = coords[1];
                if (this.createMarker) {
                    this.createMarker.geometry.setCoordinates(coords);
                } else {
                    this.createMarker = new ymaps.Placemark(coords, {}, { preset: 'islands#redIcon' });
                    this.createMapInstance.geoObjects.add(this.createMarker);
                }
            });
        });
    },

    logout() {
        if (this.isUserInActiveRide()) {
            this.showToast('Вы не можете выйти из профиля, пока находитесь в активной поездке!');
            return;
        }
        this.stopSessionCheck();
        localStorage.removeItem('uniride_user');
        state.currentUser = null;
        this.navigate('register'); // Теперь выкидывает на регистрацию
        document.getElementById('profile-name').value = '';
        document.getElementById('profile-phone').value = '';
        document.getElementById('profile-tg').value = '';
        document.getElementById('profile-vk').value = '';
        document.getElementById('btn-cancel-profile').style.display = 'none';
    },

    checkPendingStatus() {
        if (!state.pendingStudent) return;
        socket.emit('checkStatus', { studentToken: state.pendingStudent.sessionToken });
    },

    startStatusPolling() {
        this.stopStatusPolling();
        this.statusPollHandle = setInterval(() => this.checkPendingStatus(), 5000);
    },

    stopStatusPolling() {
        if (this.statusPollHandle) {
            clearInterval(this.statusPollHandle);
            this.statusPollHandle = null;
        }
    },

    startSessionCheck() {
        this.stopSessionCheck();
        this.sessionCheckHandle = setInterval(() => {
            // Отправляем проверку, даже если sessionToken отсутствует (например, сессия
            // сохранена в localStorage ещё до появления токенов) - сервер в этом случае
            // тоже ответит sessionInvalid и разлогинит, что и требуется
            if (state.currentUser) {
                socket.emit('checkStatus', { studentToken: state.currentUser.sessionToken });
            }
        }, 15000);
    },

    stopSessionCheck() {
        if (this.sessionCheckHandle) {
            clearInterval(this.sessionCheckHandle);
            this.sessionCheckHandle = null;
        }
    },

    // Сюда попадаем только если сессия ещё валидна (иначе сервер прислал бы sessionInvalid),
    // так что здесь возможны только статусы PENDING (ждём) и APPROVED (переходим в личный кабинет)
    handleStatusUpdate(student) {
        if (state.currentUser) return; // периодическая проверка активной сессии - всё ок, ничего не делаем

        if (student.status === 'APPROVED') {
            this.stopStatusPolling();
            const sessionToken = state.pendingStudent?.sessionToken;
            localStorage.removeItem('uniride_pending');
            state.pendingStudent = null;
            state.currentUser = {
                id: 'student-' + student.id,
                studentId: student.id,
                sessionToken,
                name: `${student.firstName} ${student.lastName}`,
                phone: '', tg: '', vk: ''
            };
            localStorage.setItem('uniride_user', JSON.stringify(state.currentUser));
            this.showToast('Заявка одобрена! Укажите ваши контакты.');
            this.navigate('profile');
            this.startSessionCheck();
        } else {
            state.pendingStudent = { ...student, sessionToken: state.pendingStudent?.sessionToken };
            localStorage.setItem('uniride_pending', JSON.stringify(state.pendingStudent));
        }
    },

    handleLoginResult(session) {
        const student = session.student;
        if (student.status === 'APPROVED') {
            localStorage.removeItem('uniride_pending');
            state.pendingStudent = null;
            state.currentUser = {
                id: 'student-' + student.id,
                studentId: student.id,
                sessionToken: session.sessionToken,
                name: `${student.firstName} ${student.lastName}`,
                phone: student.phoneNumber || '',
                tg: '', vk: ''
            };
            localStorage.setItem('uniride_user', JSON.stringify(state.currentUser));
            this.showToast(`Добро пожаловать, ${student.firstName}!`);
            this.navigate('dashboard');
            this.startSessionCheck();
        } else {
            state.pendingStudent = { ...student, sessionToken: session.sessionToken };
            localStorage.setItem('uniride_pending', JSON.stringify(state.pendingStudent));
            this.navigate('pending');
        }
    },

    // Логика панели администратора
    renderAdminDashboard() {
        socket.emit('getAllUsers', {});
    },

    renderUsersList() {
        const listContainer = document.getElementById('pending-users-list');
        if (!listContainer) return;

        const filterInput = document.getElementById('admin-group-filter');
        const filter = (filterInput?.value || '').trim().toLowerCase();
        const users = filter
            ? state.allUsers.filter(u => (u.groupNumber || '').toLowerCase().includes(filter))
            : state.allUsers;

        listContainer.innerHTML = '';
        if (users.length === 0) {
            listContainer.innerHTML = '<div class="empty-state text-muted" style="padding: 20px;">Пользователи не найдены</div>';
            return;
        }

        const statusLabels = { PENDING: 'Ожидает подтверждения', APPROVED: 'Одобрен' };

        users.forEach(user => {
            let actionsHtml = '';
            if (user.status === 'PENDING') {
                actionsHtml = `
                    <button class="btn btn-primary btn-sm" onclick="app.approveUser(${user.id})">Одобрить</button>
                    <button class="btn btn-danger-outline btn-sm" onclick="app.rejectUser(${user.id})">Отклонить</button>
                `;
            } else if (user.status === 'APPROVED') {
                actionsHtml = `<button class="btn btn-danger-outline btn-sm" onclick="app.revokeUser(${user.id})">Убрать доступ</button>`;
            }

            const card = document.createElement('div');
            card.className = 'card';
            card.style.display = 'flex';
            card.style.justifyContent = 'space-between';
            card.style.alignItems = 'center';
            card.style.marginBottom = '12px';

            card.innerHTML = `
                <div>
                    <div style="font-weight: 600;">${user.firstName} ${user.lastName}</div>
                    <div class="text-muted" style="font-size: 0.9rem;">Группа: ${user.groupNumber} · ${statusLabels[user.status] || user.status}</div>
                    <div class="text-muted" style="font-size: 0.85rem;">Тел: ${user.phoneNumber || '—'} · Зачётка: ${user.gradebookNumber || '—'}</div>
                </div>
                <div class="actions-row">
                    ${actionsHtml}
                </div>
            `;
            listContainer.appendChild(card);
        });
    },

    approveUser(id) {
        socket.emit('approveStudent', { studentId: id });
        this.showToast('Пользователь одобрен');
    },

    rejectUser(id) {
        if(confirm('Точно отклонить заявку?')) {
            socket.emit('rejectStudent', { studentId: id });
            this.showToast('Заявка отклонена');
        }
    },

    revokeUser(id) {
        if(confirm('Убрать доступ этому пользователю? Он больше не сможет войти.')) {
            socket.emit('revokeAccess', { studentId: id });
            this.showToast('Доступ закрыт');
        }
    },

    // Profile Logic
    populateProfileForm() {
        if (!state.currentUser) return;
        
        const nameInput = document.getElementById('profile-name');
        if (nameInput) nameInput.value = state.currentUser.name || '';
        
        document.getElementById('profile-phone').value = state.currentUser.phone || '';
        document.getElementById('profile-tg').value = state.currentUser.tg || '';
        document.getElementById('profile-vk').value = state.currentUser.vk || '';
        
        if (state.currentUser.name) {
            document.getElementById('btn-cancel-profile').style.display = 'block';
        } else {
            document.getElementById('btn-cancel-profile').style.display = 'none';
        }
    },

    saveProfile() {
        const name = document.getElementById('profile-name').value.trim();
        const phone = document.getElementById('profile-phone').value.trim();
        const tg = document.getElementById('profile-tg').value.trim();
        const vk = document.getElementById('profile-vk').value.trim();

        if (!name) {
            this.showToast('Укажите ваше имя');
            return;
        }

        state.currentUser.name = name;
        state.currentUser.phone = phone;
        state.currentUser.tg = tg;
        state.currentUser.vk = vk;

        localStorage.setItem('uniride_user', JSON.stringify(state.currentUser));

        this.showToast('Профиль сохранен');
        this.navigate('dashboard');
    },

    // Dashboard Logic
    renderDashboard() {
        if (!state.currentUser || !state.currentUser.name) {
            this.navigate('profile');
            return;
        }

        // Update Navbar Avatar
        const navAvatar = document.getElementById('nav-avatar');
        if (navAvatar) {
            navAvatar.textContent = state.currentUser.name.charAt(0).toUpperCase();
        }

        // Toggle Create Ride Button
        const btnCreate = document.getElementById('btn-create-ride');
        const sidebarActive = document.getElementById('sidebar-active-ride');
        const myRide = this.getMyActiveRide();

        if (myRide) {
            if (btnCreate) btnCreate.style.display = 'none';
            if (sidebarActive) sidebarActive.style.display = 'block';
            const routeEl = document.getElementById('sidebar-active-ride-route');
            if (routeEl) routeEl.textContent = `${myRide.departure} → ${myRide.destination}`;
            this.startRideCountdown();
        } else {
            if (btnCreate) btnCreate.style.display = 'flex';
            if (sidebarActive) sidebarActive.style.display = 'none';
            this.stopRideCountdown();
        }

        this.renderFeed();
    },

    renderFeed() {
        const feed = document.getElementById('ride-feed');
        const myFeed = document.getElementById('my-ride-feed');
        const mySection = document.getElementById('my-rides-section');
        
        const nearbyFeed = document.getElementById('nearby-ride-feed');
        const nearbySection = document.getElementById('nearby-rides-section');
        
        feed.innerHTML = '';
        myFeed.innerHTML = '';
        nearbyFeed.innerHTML = '';

        // Separate rides
        let myRides = [];
        let nearbyRides = [];
        let otherRides = [];

        // For MVP, assume user's mock location is 'Лядова'
        const myLocationKeyword = 'Лядова';

        state.rides.forEach(r => {
            if (r.status === 'ACTIVE' || r.status === 'FULL') {
                if (r.participants.find(p => p.id === state.currentUser.id)) {
                    myRides.push(r);
                } else if (r.status === 'ACTIVE') {
                    if (r.departure.includes(myLocationKeyword)) {
                        nearbyRides.push(r);
                    } else {
                        otherRides.push(r);
                    }
                }
            }
        });

        // Sort all by time
        myRides.sort((a, b) => a.time.localeCompare(b.time));
        nearbyRides.sort((a, b) => a.time.localeCompare(b.time));
        otherRides.sort((a, b) => a.time.localeCompare(b.time));

        // Helper function for DRY code
        const renderSection = (ridesArr, feedEl, sectionEl, isMine) => {
            if (ridesArr.length > 0) {
                if (sectionEl) sectionEl.style.display = 'block';
                ridesArr.forEach(ride => {
                    feedEl.insertAdjacentHTML('beforeend', this.generateRideCardHTML(ride, isMine));
                });
            } else {
                if (sectionEl) sectionEl.style.display = 'none';
            }
        };

        // Render My Rides and Nearby Rides using helper
        renderSection(myRides, myFeed, mySection, true);
        renderSection(nearbyRides, nearbyFeed, nearbySection, false);

        // Render Other Rides
        if (otherRides.length === 0 && nearbyRides.length === 0 && myRides.length === 0) {
            // Only show empty state if NO available rides exist at all
            feed.innerHTML = `
                <div class="empty-state">
                    <h3>Нет поездок</h3>
                    <p>Сейчас никто не ищет попутчиков. Создайте свою заявку!</p>
                </div>
            `;
            document.querySelector('.feed-header').style.display = 'none';
        } else {
            document.querySelector('.feed-header').style.display = 'block';
            otherRides.forEach(ride => {
                feed.insertAdjacentHTML('beforeend', this.generateRideCardHTML(ride, false));
            });
            if (otherRides.length === 0 && nearbyRides.length === 0) {
                feed.innerHTML = `<p class="text-muted">Нет других поездок</p>`;
            }
        }
    },

    generateRideCardHTML(ride, isMine) {
        const seatsOccupied = ride.participants.length;
        const seatsTotal = ride.totalSeats + 1; // including creator

        let dots = '';
        for(let i=0; i<seatsTotal; i++) {
            dots += `<div class="p-dot ${i < seatsOccupied ? 'filled' : ''}"></div>`;
        }

        return `
            <div class="ride-card" onclick="app.openRide(${ride.id})" style="${isMine ? 'border-color: var(--primary);' : ''}">
                <div class="ride-header">
                    <div class="ride-route">
                        <div>${ride.departure}</div>
                        <div class="route-arrow">↓</div>
                        <div>${ride.destination}</div>
                    </div>
                    <div class="ride-time-badge" style="${isMine ? 'background: var(--primary); color: #000;' : ''}">${ride.time}</div>
                </div>
                <div class="ride-meta">
                    <div class="ride-seats">
                        <div class="participant-dots">${dots}</div>
                        <span>Занято ${seatsOccupied} из ${seatsTotal}</span>
                    </div>
                    <div style="display: flex; align-items: center;">
                        <span style="font-size: 0.85rem; color: var(--text-muted); font-weight: 600;">${isMine ? 'Моя поездка' : ''}</span>
                        <div class="card-chevron">›</div>
                    </div>
                </div>
            </div>
        `;
    },

    // Utils
    getMyActiveRide() {
        if (!state.currentUser) return null;
        return state.rides.find(r =>
            (r.status === 'ACTIVE' || r.status === 'FULL') &&
            r.participants.some(p => p.id === state.currentUser.id)
        ) || null;
    },

    isUserInActiveRide() {
        return !!this.getMyActiveRide();
    },

    startRideCountdown() {
        this.stopRideCountdown();
        this.updateRideCountdown();
        this.rideCountdownHandle = setInterval(() => this.updateRideCountdown(), 1000);
    },

    stopRideCountdown() {
        if (this.rideCountdownHandle) {
            clearInterval(this.rideCountdownHandle);
            this.rideCountdownHandle = null;
        }
    },

    updateRideCountdown() {
        const myRide = this.getMyActiveRide();
        const countdownEl = document.getElementById('sidebar-active-ride-countdown');
        if (!myRide || !countdownEl) {
            this.stopRideCountdown();
            return;
        }

        const [hours, minutes] = myRide.time.split(':').map(Number);
        const target = new Date();
        target.setHours(hours, minutes, 0, 0);
        const diffMs = target - new Date();

        countdownEl.textContent = diffMs <= 0 ? 'Пора выезжать!' : `До отправления: ${this.formatCountdown(diffMs)}`;
    },

    formatCountdown(ms) {
        const totalSeconds = Math.max(0, Math.floor(ms / 1000));
        const h = Math.floor(totalSeconds / 3600);
        const m = Math.floor((totalSeconds % 3600) / 60);
        const s = totalSeconds % 60;
        const pad = n => String(n).padStart(2, '0');
        return h > 0 ? `${pad(h)}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
    },

    // Ride Actions
    createRide() {
        if (this.isUserInActiveRide()) {
            this.showToast('У вас уже есть активная поездка. Завершите её или отмените.');
            this.closeModal('create-ride-modal');
            return;
        }

        const dep = document.getElementById('create-departure').value;
        const dest = document.getElementById('create-destination').value;
        const time = document.getElementById('create-time').value;
        const seats = parseInt(document.getElementById('create-seats').value);

        if (!dep || !dest || !time || !seats) {
            this.showToast('Заполните все поля');
            return;
        }

        const newRide = {
            id: Date.now(), // Generate unique ID
            creator: state.currentUser.id,
            departure: dep,
            destination: dest,
            time: time,
            totalSeats: seats,
            participants: [state.currentUser], // Pass full user object
            status: 'ACTIVE',
            lat: this.currentLat,
            lon: this.currentLon
        };

        // Send to Server
        socket.emit('createRide', newRide);
        
        this.closeModal('create-ride-modal');
        this.showToast('Поездка создана!');
        this.openRide(newRide.id);
    },

    openRide(id) {
        state.activeRideId = id;
        this.navigate('ride-details');
    },

    joinRide() {
        if (this.isUserInActiveRide()) {
            this.showToast('Вы уже состоите в другой поезке!');
            return;
        }

        const ride = state.rides.find(r => r.id === state.activeRideId);
        if (!ride) return;

        if (ride.participants.find(p => p.id === state.currentUser.id)) {
            this.showToast('Вы уже участвуете');
            return;
        }

        if (ride.participants.length > ride.totalSeats) {
            this.showToast('Мест нет');
            return;
        }

        // Show confirmation modal
        this.showModal('confirm-join-modal');
    },

    executeJoinRide() {
        this.closeModal('confirm-join-modal');
        socket.emit('joinRide', {
            rideId: state.activeRideId,
            user: state.currentUser
        });
        // We do not modify local state directly. We wait for 'updateRides' from server.
        this.showToast('Запрос отправлен...');
    },

    leaveRide() {
        const ride = state.rides.find(r => r.id === state.activeRideId);
        if (!ride) return;

        const isCreator = (ride.creator === state.currentUser.id);
        const modalText = isCreator ? 
            'Вы создатель поездки. При вашем выходе поездка будет полностью отменена. Продолжить?' : 
            'Вы уверены, что хотите выйти из группы?';
        
        document.getElementById('leave-modal-text').textContent = modalText;
        this.showModal('confirm-leave-modal');
    },

    executeLeaveRide() {
        const ride = state.rides.find(r => r.id === state.activeRideId);
        if (!ride) return;

        const isCreator = (ride.creator === state.currentUser.id);

        socket.emit('leaveRide', {
            rideId: state.activeRideId,
            userId: state.currentUser.id
        });

        state.activeRideId = null;

        this.closeModal('confirm-leave-modal');
        this.showToast(isCreator ? 'Поездка отменена' : 'Вы покинули поездку');
        this.navigate('dashboard');
    },

    postponeRide() {
        socket.emit('postponeRide', { rideId: state.activeRideId, userId: state.currentUser.id });
        this.showToast('Время начала перенесено на 5 минут');
    },

    // Ride Details Render
    renderRideDetails() {
        const ride = state.rides.find(r => r.id === state.activeRideId);
        if (!ride) {
            state.activeRideId = null;
            this.navigate('dashboard');
            return;
        }

        const container = document.getElementById('ride-details-container');
        const isParticipant = ride.participants.find(p => p.id === state.currentUser.id);
        const isCreator = ride.creator === state.currentUser.id;
        const isFull = ride.status === 'FULL';

        let html = `
            <h2>${ride.departure} → ${ride.destination}</h2>
            <div style="margin-bottom: 16px;">
                <span class="ride-time-badge" style="font-size: 1rem; padding: 6px 12px; background: var(--primary); color:#000;">Время: ${ride.time}</span>
                <span style="margin-left:12px; font-weight:600; color: ${isFull ? 'var(--success)' : 'var(--warning)'}">${isFull ? 'Группа собрана' : 'Идет сбор группы'}</span>
            </div>
        `;

        if (isParticipant) {
            html += `<div class="alert alert-warning mb-3"><strong>Важно:</strong> Переведите деньги организатору ДО посадки в такси.</div>`;
            
            // Show Contacts block
            html += `<h3>Участники и контакты</h3>`;
            html += `<div class="contact-list">`;
            
            ride.participants.forEach(p => {
                const role = p.id === ride.creator ? '<span class="contact-role" style="color:var(--primary); font-weight:bold;">(Организатор)</span>' : '';
                const you = p.id === state.currentUser.id ? ' <em style="color:var(--text-muted);">(Вы)</em>' : '';
                
                // Contacts
                let contactsHtml = '';
                if (p.id !== state.currentUser.id) { // Don't show links to self
                    if (p.phone) contactsHtml += `<a href="tel:${p.phone}" class="btn btn-outline btn-sm" style="margin-right:8px;">Телефон</a>`;
                    if (p.tg) contactsHtml += `<a href="https://t.me/${p.tg.replace('@', '')}" target="_blank" class="btn btn-outline btn-sm" style="margin-right:8px;">Telegram</a>`;
                    if (p.vk) contactsHtml += `<a href="${p.vk}" target="_blank" class="btn btn-outline btn-sm">ВКонтакте</a>`;
                    if (!contactsHtml) contactsHtml = '<span class="text-muted">Нет контактов</span>';
                }

                html += `
                    <div class="contact-item">
                        <div class="contact-name">${p.name} ${role}${you}</div>
                        <div class="contact-links" style="margin-top: 8px;">
                            ${contactsHtml}
                        </div>
                    </div>
                `;
            });

            const emptySlots = (ride.totalSeats + 1) - ride.participants.length;
            for(let i=0; i<emptySlots; i++) {
                html += `
                    <div class="contact-item" style="color: var(--text-muted); font-style: italic;">
                        Ожидание попутчика...
                    </div>
                `;
            }
            html += `</div>`;

            // Actions for Participants
            if (isCreator) {
                html += `
                    <div class="actions-row mt-3">
                        <button class="btn btn-outline flex-1" onclick="app.postponeRide()">+5 мин к началу</button>
                        <button class="btn btn-danger-outline flex-1" onclick="app.leaveRide()">Отменить поездку</button>
                    </div>
                `;
            } else {
                html += `
                    <div class="actions-row mt-3">
                        <button class="btn btn-danger-outline w-100" onclick="app.leaveRide()">Покинуть группу</button>
                    </div>
                `;
            }
        } else {
            // Not a participant view
            const creatorInfo = ride.participants.find(p => p.id === ride.creator) || { name: 'Студент' };
            html += `
                <p>Организатор: <strong>${creatorInfo.name}</strong></p>
                <p class="text-muted mb-3">Свободных мест: ${(ride.totalSeats + 1) - ride.participants.length}</p>
                
                <div class="alert alert-info mb-3">
                    Контакты участников откроются только после присоединения к группе.
                </div>

                <button class="btn btn-primary w-100 btn-lg" onclick="app.joinRide()">Присоединиться</button>
            `;
        }

        container.innerHTML = html;

        // Yandex Map Render
        const mapContainer = document.getElementById('details-map-container');
        if (ride.lat && ride.lon && window.ymaps) {
            mapContainer.style.display = 'block';
            ymaps.ready(() => {
                if (!this.detailsMapInstance) {
                    document.getElementById('details-map').innerHTML = '';
                    this.detailsMapInstance = new ymaps.Map('details-map', {
                        center: [ride.lat, ride.lon],
                        zoom: 16,
                        controls: ['zoomControl']
                    });
                    this.detailsMarker = new ymaps.Placemark([ride.lat, ride.lon], { balloonContent: 'Место встречи' }, { preset: 'islands#redIcon' });
                    this.detailsMapInstance.geoObjects.add(this.detailsMarker);
                } else {
                    this.detailsMapInstance.setCenter([ride.lat, ride.lon]);
                    this.detailsMarker.geometry.setCoordinates([ride.lat, ride.lon]);
                }
            });
        } else {
            if (mapContainer) mapContainer.style.display = 'none';
        }
    }
};

// Start
document.addEventListener('DOMContentLoaded', () => app.init());

document.addEventListener('click', (e) => {
    if (!e.target.closest('.custom-select')) {
        document.querySelectorAll('.custom-select').forEach(el => el.classList.remove('open'));
    }
    if (!e.target.closest('#combo-departure') && !e.target.closest('#combo-destination')) {
        const d1 = document.getElementById('options-departure');
        const d2 = document.getElementById('options-destination');
        if (d1) d1.style.display = 'none';
        if (d2) d2.style.display = 'none';
    }
});