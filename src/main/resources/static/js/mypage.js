function toggleEdit(type) {
    const section = document.getElementById(`${type}-section`);
    if (!section) {
        console.error(`${type}-section not found.`);
        return;
    }

    section.querySelectorAll('.display-mode').forEach((element) => {
        element.style.display = element.style.display === 'none' ? '' : 'none';
    });

    section.querySelectorAll('.edit-mode').forEach((element) => {
        element.style.display = element.style.display === 'none' ? '' : 'none';
    });
}

function getEditValue(type) {
    const inputId = type === 'password' ? 'input-password' : `input-${type}`;
    return document.getElementById(inputId)?.value ?? '';
}

function saveEdit(type) {
    const userNum = document.getElementById('user-num')?.value;
    const newValue = getEditValue(type);

    fetch('/user/update-profile', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: `num=${encodeURIComponent(userNum || '')}&type=${encodeURIComponent(type)}&value=${encodeURIComponent(newValue)}`
    })
        .then((response) => response.text())
        .then((result) => {
            if (result === 'success') {
                location.reload();
                return;
            }

            alert('Unable to save the profile change.');
        })
        .catch(() => {
            alert('Unable to save the profile change.');
        });
}

function bindNotificationToggles() {
    document.querySelectorAll('.toggle-input').forEach((toggle) => {
        toggle.addEventListener('change', function handleToggleChange() {
            const { type } = this.dataset;
            const status = this.checked ? 1 : 0;

            fetch('/api/user/settings/notification', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ type, status })
            })
                .then((response) => response.json())
                .then((data) => {
                    if (!data.ok) {
                        this.checked = !this.checked;
                        alert('Unable to update the notification setting.');
                        return;
                    }

                    // 알림창 기능
                    window.dispatchEvent(new CustomEvent('stoxle-notification-setting-change', {
                        detail: { type, status }
                    }));
                })
                .catch(() => {
                    this.checked = !this.checked;
                    alert('Unable to update the notification setting.');
                });
        });
    });
}

function animateCards() {
    const cards = document.querySelectorAll('.card');
    const observer = new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
            if (!entry.isIntersecting) {
                return;
            }

            entry.target.style.opacity = '1';
            entry.target.style.transform = 'translateY(0)';
        });
    }, { threshold: 0.1 });

    cards.forEach((card) => {
        card.style.opacity = '0';
        card.style.transform = 'translateY(20px)';
        card.style.transition = 'opacity 0.5s, transform 0.5s';
        observer.observe(card);
    });
}

document.addEventListener('DOMContentLoaded', () => {
    bindNotificationToggles();
});

window.addEventListener('load', animateCards);
