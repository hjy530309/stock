let badgeRefreshTimer = null;
let alertToastInitialized = false;
let latestSeenAlertId = null;
let latestAlertItems = [];
let pendingBadgeAlertIds = new Set();

let alertTypePreferences = {
    stock: true,
    comment: true
};

const ALERT_POLL_INTERVAL = 5000;
const ALERT_NOTIFICATION_CLOSE_DELAY = 7000;
const ALERT_NOTIFICATION_TAG_PREFIX = 'stoxle-alert-';
const COMMENT_ALERT_TYPE = '\uB313\uAE00';

function createAlertPollingTask(task) {
    let running = false;

    return async () => {
        if (running) {
            return;
        }

        running = true;
        try {
            await task();
        } finally {
            running = false;
        }
    };
}

function initAlertPanel() {
    const bellBtn = document.getElementById('bellBtn');
    const alertPanel = document.getElementById('alertPanel');
    if (!bellBtn || !alertPanel) {
        return;
    }

    initSystemNotificationControls();
    const refreshBadgeTask = createAlertPollingTask(refreshBadge);

    bellBtn.addEventListener('click', async (event) => {
        event.preventDefault();
        event.stopPropagation();

        const isOpen = alertPanel.classList.contains('open');
        alertPanel.classList.toggle('open');

        if (!isOpen) {
            const activeTab = alertPanel.querySelector('.alertTab.active')?.dataset.tab || 'history';
            if (activeTab === 'history') {
                await loadAlertPanel();
                await fetch('/api/alerts/read', {method: 'POST'});
                acknowledgeVisibleAlerts();
            } else {
                await loadWatchlistPanel();
            }
        }
    });

    document.addEventListener('visibilitychange', () => {
        updateNotificationPermissionUi();
        if (!document.hidden) {
            refreshBadgeTask();
        }
    });

    window.addEventListener('focus', () => {
        updateNotificationPermissionUi();
        refreshBadgeTask();
    });

    document.addEventListener('click', (event) => {
        if (!event.target.closest('.inlineAlert') && !event.target.closest('.bellWrapper')) {
            alertPanel.classList.remove('open');
        }
    });

    alertPanel.addEventListener('click', (event) => {
        event.stopPropagation();
    });

    alertPanel.querySelectorAll('.alertTab').forEach((tab) => {
        tab.addEventListener('click', async () => {
            alertPanel.querySelectorAll('.alertTab').forEach((targetTab) => targetTab.classList.remove('active'));
            tab.classList.add('active');

            const tabType = tab.dataset.tab;
            document.getElementById('alertTabHistory').style.display = tabType === 'history' ? '' : 'none';
            document.getElementById('alertTabWatchlist').style.display = tabType === 'watchlist' ? '' : 'none';

            if (tabType === 'history') {
                await loadAlertPanel();
                await fetch('/api/alerts/read', {method: 'POST'});
                acknowledgeVisibleAlerts();
            } else {
                await loadWatchlistPanel();
            }
        });
    });

    document.getElementById('alertReadAllBtn')?.addEventListener('click', async () => {
        await fetch('/api/alerts/read', {method: 'POST'});
        document.querySelectorAll('.alertItem').forEach((item) => item.classList.remove('unread'));
        acknowledgeVisibleAlerts();
    });

    refreshBadgeTask();

    if (badgeRefreshTimer) {
        clearInterval(badgeRefreshTimer);
    }
    badgeRefreshTimer = setInterval(refreshBadgeTask, ALERT_POLL_INTERVAL);
}

async function refreshBadge() {
    try {
        const response = await fetch('/api/alerts');
        const data = await response.json();
        const badge = document.getElementById('bellBadge');
        if (!badge) {
            return;
        }

        syncAlertTypePreferences(data.notifySettings);
        latestAlertItems = Array.isArray(data.alerts) ? data.alerts : [];
        handleAlertToasts(latestAlertItems);
        syncPendingBadgeAlerts(latestAlertItems);
        updateBellBadge(latestAlertItems);
    } catch (error) {
        // Ignore temporary polling failures.
    }
}

function canUseBrowserNotifications() {
    return 'Notification' in window
        && (
            window.isSecureContext
            || location.hostname === 'localhost'
            || location.hostname === '127.0.0.1'
            || location.hostname === '[::1]'
        );
}

function getBrowserNotificationState() {
    if (!canUseBrowserNotifications()) {
        return 'unsupported';
    }

    return Notification.permission;
}

function isBrowserNotificationGranted() {
    return getBrowserNotificationState() === 'granted';
}

function initSystemNotificationControls() {
    const permissionBtn = document.getElementById('alertPermissionBtn');
    if (permissionBtn) {
        permissionBtn.addEventListener('click', async (event) => {
            event.stopPropagation();
            const granted = await requestSystemNotificationPermission();
            if (granted) {
                showPermissionConfirmationNotification();
            }
        });
    }

    window.addEventListener('stoxle-notification-setting-change', (event) => {
        syncAlertTypePreferences(event.detail);
        updateBellBadge(latestAlertItems);
    });

    updateNotificationPermissionUi();
}

function syncAlertTypePreferences(settings) {
    if (!settings || typeof settings !== 'object') {
        return;
    }

    if (Object.prototype.hasOwnProperty.call(settings, 'stock')) {
        alertTypePreferences.stock = settings.stock === true || settings.stock === 1;
    }

    if (Object.prototype.hasOwnProperty.call(settings, 'comment')) {
        alertTypePreferences.comment = settings.comment === true || settings.comment === 1;
    }

    updateNotificationPermissionUi();
}

function isAlertTypeEnabled(alert) {
    const preferenceKey = alert?.alertType === COMMENT_ALERT_TYPE ? 'comment' : 'stock';
    return alertTypePreferences[preferenceKey] !== false;
}

function getVisibleUnreadCount(alerts) {
    if (!Array.isArray(alerts)) {
        return 0;
    }

    return alerts.filter((alert) =>
        alert?.id != null
        && pendingBadgeAlertIds.has(Number(alert.id))
        && isAlertTypeEnabled(alert)
    ).length;
}

function updateBellBadge(alerts) {
    const badge = document.getElementById('bellBadge');
    if (!badge) {
        return;
    }

    const unreadCount = getVisibleUnreadCount(alerts);
    if (unreadCount > 0) {
        badge.textContent = unreadCount > 99 ? '99+' : unreadCount;
        badge.classList.add('show');
        return;
    }

    badge.classList.remove('show');
}

function syncPendingBadgeAlerts(alerts) {
    if (!Array.isArray(alerts)) {
        return;
    }

    const currentAlertsById = new Map();
    alerts.forEach((alert) => {
        if (alert?.id == null) {
            return;
        }

        const numericId = Number(alert.id);
        if (!Number.isFinite(numericId)) {
            return;
        }

        currentAlertsById.set(numericId, alert);
        if (!alert.read) {
            pendingBadgeAlertIds.add(numericId);
        }
    });

    Array.from(pendingBadgeAlertIds).forEach((alertId) => {
        const currentAlert = currentAlertsById.get(alertId);
        if (!currentAlert || currentAlert.read) {
            pendingBadgeAlertIds.delete(alertId);
        }
    });
}

function acknowledgeVisibleAlerts() {
    pendingBadgeAlertIds.clear();
    latestAlertItems = latestAlertItems.map((alert) => ({
        ...alert,
        read: true
    }));
    updateBellBadge(latestAlertItems);
}

async function requestSystemNotificationPermission() {
    if (!canUseBrowserNotifications()) {
        updateNotificationPermissionUi();
        return false;
    }

    if (Notification.permission === 'granted') {
        updateNotificationPermissionUi();
        return true;
    }

    if (Notification.permission === 'denied') {
        updateNotificationPermissionUi();
        return false;
    }

    try {
        const permission = await Notification.requestPermission();
        updateNotificationPermissionUi();
        return permission === 'granted';
    } catch (error) {
        updateNotificationPermissionUi();
        return false;
    }
}

function updateNotificationPermissionUi() {
    const permissionBar = document.getElementById('alertPermissionBar');
    const permissionText = document.getElementById('alertPermissionText');
    const permissionBtn = document.getElementById('alertPermissionBtn');
    if (!permissionBar || !permissionText || !permissionBtn) {
        return;
    }

    const permission = getBrowserNotificationState();
    permissionBar.dataset.permission = permission;
    permissionBar.hidden = false;
    permissionBtn.hidden = false;
    permissionBtn.disabled = false;

    if (permission === 'granted') {
        permissionBar.hidden = true;
        return;
    }

    if (permission === 'denied') {
        permissionText.textContent = '\uBE0C\uB77C\uC6B0\uC800 \uC54C\uB9BC\uC774 \uCC28\uB2E8\uB418\uC5B4 \uC788\uC2B5\uB2C8\uB2E4. \uC8FC\uC18C\uCC3D \uC0AC\uC774\uD2B8 \uAD8C\uD55C\uC5D0\uC11C \uC54C\uB9BC\uC744 \uD5C8\uC6A9\uD574 \uC8FC\uC138\uC694.';
        permissionBtn.textContent = '\uC124\uC815 \uD655\uC778';
        permissionBtn.disabled = true;
        return;
    }

    if (permission === 'unsupported') {
        permissionText.textContent = '\uBE0C\uB77C\uC6B0\uC800 \uC54C\uB9BC\uC740 HTTPS \uB610\uB294 localhost \uD658\uACBD\uC5D0\uC11C\uB9CC \uC0AC\uC6A9\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.';
        permissionBtn.hidden = true;
        return;
    }

    permissionText.textContent = '\uBE0C\uB77C\uC6B0\uC800 \uC54C\uB9BC\uC744 \uCF1C\uB450\uBA74 \uC0AC\uC774\uD2B8\uB97C \uBC97\uC5B4\uB098 \uC788\uB294 \uC0C1\uD0DC\uC5D0\uC11C\uB3C4 PC \uC54C\uB9BC\uC73C\uB85C \uBC1B\uC744 \uC218 \uC788\uC2B5\uB2C8\uB2E4.';
    permissionBtn.textContent = '\uBE0C\uB77C\uC6B0\uC800 \uC54C\uB9BC \uCF1C\uAE30';
}

function showPermissionConfirmationNotification() {
    showSystemNotification({
        id: 'permission-preview',
        title: '\uBE0C\uB77C\uC6B0\uC800 \uC54C\uB9BC \uD65C\uC131\uD654',
        message: '\uC774\uC81C \uC0C8 \uC54C\uB9BC\uC744 PC \uC54C\uB9BC\uC73C\uB85C \uD45C\uC2DC\uD569\uB2C8\uB2E4.',
        force: true
    });
}

function shouldShowSystemNotification(alert) {
    if (!isBrowserNotificationGranted()) {
        return false;
    }

    if (!alert?.force && !isAlertTypeEnabled(alert)) {
        return false;
    }

    return true;
}

function showSystemNotification(alert) {
    if (!shouldShowSystemNotification(alert)) {
        return;
    }

    const title = alert?.title || alert?.stockName || '\uC0C8 \uC54C\uB9BC';
    const body = alert?.message || buildAlertFallbackMessage(alert);
    const tagId = alert?.id != null ? String(alert.id) : Date.now().toString();

    try {
        const notification = new Notification(title, {
            body,
            tag: ALERT_NOTIFICATION_TAG_PREFIX + tagId
        });

        notification.onclick = () => {
            notification.close();
            window.focus();

            const link = resolveAlertLink(alert);
            if (link) {
                const targetUrl = new URL(link, window.location.origin).href;
                if (location.href !== targetUrl) {
                    location.href = targetUrl;
                }
            }
        };

        window.setTimeout(() => notification.close(), ALERT_NOTIFICATION_CLOSE_DELAY);
    } catch (error) {
        // Ignore notification failures on unsupported browsers.
    }
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function resolveAlertLink(alert) {
    if (alert?.link) {
        return alert.link;
    }

    if (alert?.alertType === COMMENT_ALERT_TYPE) {
        return `/community/detail?board_id=${encodeURIComponent(alert?.stockCode ?? '')}`;
    }

    return `/market/${encodeURIComponent(alert?.stockCode ?? '')}`;
}

function resolveAlertDotClass(alert) {
    if (
        alert?.variant === 'rise'
        || alert?.variant === 'fall'
        || alert?.variant === 'surge'
        || alert?.variant === 'comment'
        || alert?.variant === 'anomaly'
    ) {
        return alert.variant;
    }

    if (alert?.alertType === COMMENT_ALERT_TYPE) {
        return 'comment';
    }

    if (alert?.alertType === '\uC0C1\uC2B9') {
        return 'rise';
    }

    if (alert?.alertType === '\uAC00\uACA9\uAE09\uB4F1') {
        return 'rise';
    }

    if (alert?.alertType === '\uD558\uB77D') {
        return 'fall';
    }

    if (alert?.alertType === '\uAC00\uACA9\uAE09\uB77D' || alert?.alertType === '\uAE09\uB77D') {
        return 'fall';
    }

    if (
        alert?.alertType === '\uAE09\uB4F1'
        || alert?.alertType === '\uAC70\uB798\uB7C9\uAE09\uB4F1'
        || alert?.alertType === '\uC2E4\uC2DC\uAC04_\uAE09\uB4F1'
        || alert?.alertType === '\uC774\uC0C1\uAC70\uB798_\uC2EC\uAC01'
        || alert?.alertType === '\uACBD\uACE0'
    ) {
        return 'surge';
    }

    return 'anomaly';
}

function formatAlertPrice(price) {
    const text = String(price ?? '').trim();
    if (!text) {
        return '';
    }
    if (text.endsWith('\uC6D0')) {
        return text;
    }

    const normalized = text.replace(/,/g, '');
    if (/^[+-]?\d+(?:\.\d+)?$/.test(normalized)) {
        return `${Number(normalized).toLocaleString()}\uC6D0`;
    }

    return `${text}\uC6D0`;
}

function buildAlertFallbackMessage(alert) {
    if (alert?.alertType === COMMENT_ALERT_TYPE) {
        const actor = String(alert?.changeRate ?? '').trim();
        const target = String(alert?.stockName ?? '').trim();
        return `${actor}\uB2D8\uC774 '${target}' \uAC8C\uC2DC\uAE00\uC5D0 \uB313\uAE00\uC744 \uB0A8\uACBC\uC2B5\uB2C8\uB2E4.`;
    }

    const changeRate = String(alert?.changeRate ?? '').trim();
    const priceText = formatAlertPrice(alert?.price);
    const alertType = String(alert?.alertType ?? '').trim();
    const dotClass = resolveAlertDotClass(alert);

    if (dotClass === 'rise') {
        return ['\u25B2', changeRate, alertType, priceText ? `\u00B7 ${priceText}` : '']
            .filter(Boolean)
            .join(' ');
    }

    if (dotClass === 'fall') {
        return ['\u25BC', changeRate, alertType, priceText ? `\u00B7 ${priceText}` : '']
            .filter(Boolean)
            .join(' ');
    }

    return [
        alertType || '\uC774\uC0C1 \uAC70\uB798 \uAC10\uC9C0',
        changeRate ? `\uBCC0\uB3D9\uB960 ${changeRate}` : '',
        priceText ? `\uD604\uC7AC\uAC00 ${priceText}` : ''
    ]
        .filter(Boolean)
        .join(' \u00B7 ');
}

function renderAlertItem(alert) {
    const link = resolveAlertLink(alert);
    const dotClass = resolveAlertDotClass(alert);
    const title = escapeHtml(alert?.title || alert?.stockName || '\uC0C8 \uC54C\uB9BC');
    const message = escapeHtml(alert?.message || buildAlertFallbackMessage(alert));
    const safeLink = escapeHtml(link);

    return `
        <div class="alertItem ${!alert?.read ? 'unread' : ''}"
             onclick="location.href='${safeLink}'">
            ${buildAlertDotMarkup(dotClass)}
            <div class="alertItemText">
                <div class="alertItemName">${title}</div>
                <div class="alertItemDesc">${message}</div>
                <div class="alertItemTime">${formatAlertTime(alert?.createdAt)}</div>
            </div>
        </div>`;
}

function buildAlertDotMarkup(variant) {
    return `
        <div class="alertDot ${variant}" aria-hidden="true">
            ${getAlertDotSvg(variant)}
        </div>`;
}

function getAlertDotSvg(variant) {
    switch (variant) {
        case 'rise':
            return `
                <svg viewBox="0 0 24 24" class="alertDotIcon alertDotIconSolid" focusable="false">
                    <path class="alertDotPrimaryFill" d="M12 5.5 20 18.5H4z" />
                </svg>`;
        case 'fall':
            return `
                <svg viewBox="0 0 24 24" class="alertDotIcon alertDotIconSolid" focusable="false">
                    <path class="alertDotPrimaryFill" d="M12 18.5 4 5.5h16z" />
                </svg>`;
        case 'surge':
            return `
                <svg viewBox="0 0 24 24" class="alertDotIcon" focusable="false">
                    <path class="alertDotPrimaryFill" d="M12 4.5 21 19.5H3z" />
                    <path class="alertDotAccentStroke" d="M12 9v4.4" />
                    <circle cx="12" cy="16.6" r="1.1" class="alertDotAccentFill" />
                </svg>`;
        case 'comment':
            return `
                <svg viewBox="0 0 24 24" class="alertDotIcon alertDotIconSolid" focusable="false">
                    <path class="alertDotPrimaryFill" d="M6.5 6.5h11a3 3 0 0 1 3 3v4.6a3 3 0 0 1-3 3H12l-4.8 3.4V17.1h-.7a3 3 0 0 1-3-3V9.5a3 3 0 0 1 3-3Z" />
                </svg>`;
        default:
            return `
                <svg viewBox="0 0 24 24" class="alertDotIcon" focusable="false">
                    <path class="alertDotPrimaryFill" d="M12 4.5 21 19.5H3z" />
                    <path class="alertDotAccentStroke" d="M12 9v4.4" />
                    <circle cx="12" cy="16.6" r="1.1" class="alertDotAccentFill" />
                </svg>`;
    }
}

function handleAlertToasts(alerts) {
    if (!Array.isArray(alerts) || alerts.length === 0) {
        return;
    }

    const sortedAlerts = alerts
        .filter((alert) => alert && alert.id != null)
        .slice()
        .sort((left, right) => Number(left.id) - Number(right.id));

    if (sortedAlerts.length === 0) {
        return;
    }

    if (!alertToastInitialized) {
        latestSeenAlertId = Number(sortedAlerts[sortedAlerts.length - 1].id);
        alertToastInitialized = true;
        return;
    }

    const newAlerts = sortedAlerts.filter((alert) =>
        !alert.read && Number(alert.id) > Number(latestSeenAlertId || 0)
    );

    if (newAlerts.length > 0) {
        newAlerts.forEach((alert) => {
            showSystemNotification(alert);
        });
        latestSeenAlertId = Number(newAlerts[newAlerts.length - 1].id);
        return;
    }

    latestSeenAlertId = Math.max(
        Number(latestSeenAlertId || 0),
        Number(sortedAlerts[sortedAlerts.length - 1].id)
    );
}

async function loadAlertPanel() {
    const list = document.getElementById('alertList');
    if (!list) {
        return;
    }

    try {
        const response = await fetch('/api/alerts');
        const data = await response.json();
        const alerts = data.alerts || [];

        if (alerts.length === 0) {
            list.innerHTML = '<div class="alertEmpty">\uC54C\uB9BC\uC774 \uC5C6\uC2B5\uB2C8\uB2E4</div>';
            return;
        }

        list.innerHTML = alerts.map(renderAlertItem).join('');
    } catch (error) {
        list.innerHTML = '<div class="alertEmpty">\uBD88\uB7EC\uC624\uAE30 \uC2E4\uD328</div>';
    }
}

async function loadWatchlistPanel() {
    const list = document.getElementById('watchlistPanelList');
    if (!list) {
        return;
    }

    try {
        const response = await fetch('/api/watchlist');
        const items = await response.json();

        if (!items || items.length === 0) {
            list.innerHTML = `
                <div class="alertEmpty">
                    \uB4F1\uB85D\uB41C \uAD00\uC2EC\uC885\uBAA9\uC774 \uC5C6\uC2B5\uB2C8\uB2E4
                    <br>
                    <small style="color:#bbb;font-size:11px;margin-top:6px;display:block">
                        \uC885\uBAA9 \uD398\uC774\uC9C0\uC5D0\uC11C \u2665 \uBC84\uD2BC\uC73C\uB85C \uCD94\uAC00\uD574 \uC8FC\uC138\uC694
                    </small>
                </div>`;
            return;
        }

        list.innerHTML = items.map((item) => `
            <div class="watchlistPanelItem" onclick="location.href='/market/${item.stockCode}'">
                <div class="watchlistItemInfo">
                    <div class="watchlistItemName">${escapeHtml(item.stockName)}</div>
                    <div class="watchlistItemCode">${escapeHtml(item.stockCode)} &nbsp;\u00B7&nbsp; \u00B13% \uC54C\uB9BC \uC124\uC815\uB428</div>
                </div>
                <button
                    class="watchlistRemoveBtn"
                    data-code="${escapeHtml(item.stockCode)}"
                    data-name="${escapeHtml(item.stockName)}"
                    onclick="event.stopPropagation(); removeFromPanel(this)">
                    \uC54C\uB9BC \uD574\uC81C
                </button>
            </div>
        `).join('');
    } catch (error) {
        list.innerHTML = '<div class="alertEmpty">\uBD88\uB7EC\uC624\uAE30 \uC2E4\uD328</div>';
    }
}

async function removeFromPanel(btn) {
    const code = btn.dataset.code;
    await fetch(`/api/watchlist/${code}`, {method: 'DELETE'});

    if (typeof watchingSet !== 'undefined') {
        watchingSet.delete(code);
    }

    btn.closest('.watchlistPanelItem')?.remove();

    document.querySelectorAll(`.heartBtn[data-code="${code}"]`).forEach((heartBtn) => {
        heartBtn.classList.remove('watching');
        heartBtn.textContent = '\u2661';
    });

    if (typeof selectedCode !== 'undefined' && code === selectedCode) {
        const panelBtn = document.getElementById('panelWatchBtn');
        if (panelBtn && typeof setPanelWatchBtn === 'function') {
            setPanelWatchBtn(panelBtn, false);
        }
        if (typeof updateAlertNote === 'function') {
            updateAlertNote(false);
        }
    }

    const list = document.getElementById('watchlistPanelList');
    if (list && list.querySelectorAll('.watchlistPanelItem').length === 0) {
        list.innerHTML = `
            <div class="alertEmpty">
                \uB4F1\uB85D\uB41C \uAD00\uC2EC\uC885\uBAA9\uC774 \uC5C6\uC2B5\uB2C8\uB2E4
                <br>
                <small style="color:#bbb;font-size:11px;margin-top:6px;display:block">
                    \uC885\uBAA9 \uD398\uC774\uC9C0\uC5D0\uC11C \u2665 \uBC84\uD2BC\uC73C\uB85C \uCD94\uAC00\uD574 \uC8FC\uC138\uC694
                </small>
            </div>`;
    }
}

function formatAlertTime(isoString) {
    try {
        const createdAt = new Date(isoString);
        const diffMinutes = Math.floor((Date.now() - createdAt.getTime()) / 60000);

        if (diffMinutes < 1) {
            return '\uBC29\uAE08 \uC804';
        }
        if (diffMinutes < 60) {
            return `${diffMinutes}\uBD84 \uC804`;
        }
        if (diffMinutes < 1440) {
            return `${Math.floor(diffMinutes / 60)}\uC2DC\uAC04 \uC804`;
        }
        return `${Math.floor(diffMinutes / 1440)}\uC77C \uC804`;
    } catch (error) {
        return '';
    }
}

document.addEventListener('DOMContentLoaded', () => {
    initAlertPanel();
});
