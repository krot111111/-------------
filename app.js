/**
 * UniRide - Frontend Logic Prototype
 * This acts as a mockup for the future Java + PostgreSQL backend.
 */

// Application State
const state = {
    currentUser: null, // Loaded from localStorage
    currentView: 'view-profile',
    activeRideId: null,
    rides: [] // Fetched from server
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
        else if (event === 'leaveRide') { msg.rideId = data.rideId; msg.userId = data.userId; msg.isCreator = data.isCreator; }
        
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

const app = {
    init() {
        const savedUser = localStorage.getItem('uniride_user');
        if (savedUser) {
            state.currentUser = JSON.parse(savedUser);
            this.navigate('dashboard');
        } else {
            this.navigate('profile');
        }
        
        // Preset time input
        const now = new Date();
        now.setMinutes(now.getMinutes() + 15);
        document.getElementById('create-time').value = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
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
        if (viewId === 'view-auth') {
            nav.style.display = 'none';
        } else {
            nav.style.display = 'block';
        }

        // View specific logic
        if (viewId === 'view-dashboard') this.renderDashboard();
        if (viewId === 'view-profile') this.populateProfileForm();
        if (viewId === 'view-ride-details') this.renderRideDetails();
    },

    showToast(message) {
        const toast = document.getElementById('toast');
        toast.textContent = message;
        toast.classList.add('show');
        setTimeout(() => toast.classList.remove('show'), 3000);
    },

    showModal(modalId) {
        document.getElementById(modalId).classList.add('active');
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

    logout() {
        if (this.isUserInActiveRide()) {
            this.showToast('Вы не можете выйти из профиля, пока находитесь в активной поездке!');
            return;
        }
        localStorage.removeItem('uniride_user');
        state.currentUser = null;
        this.navigate('profile');
        document.getElementById('profile-name').value = '';
        document.getElementById('profile-phone').value = '';
        document.getElementById('profile-tg').value = '';
        document.getElementById('profile-vk').value = '';
        document.getElementById('btn-cancel-profile').style.display = 'none';
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

        if (!state.currentUser) {
            state.currentUser = {
                id: 'u' + Date.now()
            };
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
        
        if (this.isUserInActiveRide()) {
            if (btnCreate) btnCreate.style.display = 'none';
            if (sidebarActive) sidebarActive.style.display = 'block';
        } else {
            if (btnCreate) btnCreate.style.display = 'flex';
            if (sidebarActive) sidebarActive.style.display = 'none';
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
                    <div class="empty-icon">🚖</div>
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
    isUserInActiveRide() {
        if (!state.currentUser) return false;
        return state.rides.some(r => 
            (r.status === 'ACTIVE' || r.status === 'FULL') && 
            r.participants.some(p => p.id === state.currentUser.id)
        );
    },

    getActiveRideId() {
        if (!state.currentUser) return null;
        const ride = state.rides.find(r => 
            (r.status === 'ACTIVE' || r.status === 'FULL') && 
            r.participants.some(p => p.id === state.currentUser.id)
        );
        return ride ? ride.id : null;
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
            status: 'ACTIVE'
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
            this.showToast('Вы уже состоите в другой поездке!');
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
            userId: state.currentUser.id,
            isCreator: isCreator
        });

        state.activeRideId = null;

        this.closeModal('confirm-leave-modal');
        this.showToast(isCreator ? 'Поездка отменена' : 'Вы покинули поездку');
        this.navigate('dashboard');
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
                        <button class="btn btn-danger-outline w-100" onclick="app.leaveRide()">Отменить поездку</button>
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
    }
};

// Start
document.addEventListener('DOMContentLoaded', () => app.init());

document.addEventListener('click', (e) => {
    if (!e.target.closest('.custom-select')) {
        document.querySelectorAll('.custom-select').forEach(el => el.classList.remove('open'));
    }
});
