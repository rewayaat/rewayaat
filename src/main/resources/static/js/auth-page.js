(function() {
    function apiJSON(url, options) {
        var opts = options || {};
        opts.credentials = 'same-origin';
        if (opts.body && (!opts.headers || !opts.headers['Content-Type'])) {
            opts.headers = opts.headers || {};
            opts.headers['Content-Type'] = 'application/json';
        }
        return fetch(url, opts).then(function(resp) {
            return resp.json().catch(function() { return {}; }).then(function(data) {
                return { ok: resp.ok, status: resp.status, data: data };
            });
        });
    }

    function qs(key) {
        var url = new URL(window.location.href);
        return url.searchParams.get(key) || '';
    }

    function removeQueryParam(key) {
        var url = new URL(window.location.href);
        if (!url.searchParams.has(key)) {
            return;
        }
        url.searchParams.delete(key);
        window.history.replaceState({}, '', url.toString());
    }

    function normalizeReturnPath(value) {
        if (!value) {
            return '/';
        }
        var decoded = value;
        try {
            decoded = decodeURIComponent(value);
        } catch (ignore) {
            decoded = value;
        }
        if (decoded.indexOf('http://') === 0 || decoded.indexOf('https://') === 0) {
            try {
                var absolute = new URL(decoded);
                if (absolute.origin !== window.location.origin) {
                    return '/';
                }
                decoded = absolute.pathname + absolute.search + absolute.hash;
            } catch (ignore2) {
                return '/';
            }
        }
        if (!decoded || decoded.charAt(0) !== '/') {
            return '/';
        }
        if (decoded.indexOf('/signin.html') === 0) {
            return '/';
        }
        return decoded;
    }

    function byId(id) {
        return document.getElementById(id);
    }

    function setAlert(message, level) {
        var alertEl = byId('authAlert');
        if (!alertEl) {
            return;
        }
        if (!message) {
            alertEl.className = 'alert d-none';
            alertEl.textContent = '';
            return;
        }
        var mapped = level || 'info';
        alertEl.className = 'alert alert-' + mapped;
        alertEl.textContent = message;
    }

    function appendDebugUrl(message, debugUrl) {
        if (!debugUrl) {
            return message;
        }
        return message + ' Local debug link: ' + debugUrl;
    }

    function passwordIssues(password) {
        var value = password || '';
        var issues = [];
        if (value.length < 6) {
            issues.push('at least 6 characters');
        }
        if (/\s/.test(value)) {
            issues.push('no spaces');
        }
        return issues;
    }

    var forms = {
        login: byId('authLoginForm'),
        register: byId('authRegisterForm'),
        resetRequest: byId('authResetRequestForm'),
        resetConfirm: byId('authResetConfirmForm')
    };

    var modeButtons = {
        login: byId('authModeLogin'),
        register: byId('authModeRegister'),
        reset: byId('authModeReset')
    };

    function setFormDisabled(formEl, disabled) {
        if (!formEl) {
            return;
        }
        var fields = formEl.querySelectorAll('input, button');
        fields.forEach(function(field) {
            field.disabled = !!disabled;
        });
    }

    function showMode(mode) {
        var selected = mode || 'login';
        var hasResetToken = !!qs('reset_token');
        if (selected === 'reset-confirm' || hasResetToken) {
            selected = 'reset-confirm';
        }
        Object.keys(forms).forEach(function(key) {
            var form = forms[key];
            if (!form) {
                return;
            }
            var shouldShow = (key === 'resetConfirm' && selected === 'reset-confirm') ||
                (key === 'resetRequest' && selected === 'reset-request') ||
                (key === 'login' && selected === 'login') ||
                (key === 'register' && selected === 'register');
            form.classList.toggle('d-none', !shouldShow);
        });
        Object.keys(modeButtons).forEach(function(key) {
            var active = false;
            if (key === 'reset') {
                active = (selected === 'reset-request' || selected === 'reset-confirm');
            } else {
                active = selected === key;
            }
            modeButtons[key].classList.toggle('active', active);
        });
        var url = new URL(window.location.href);
        if (selected === 'reset-confirm') {
            url.searchParams.set('mode', 'reset');
        } else if (selected === 'reset-request') {
            url.searchParams.set('mode', 'reset');
        } else {
            url.searchParams.set('mode', selected);
        }
        window.history.replaceState({}, '', url.toString());
    }

    function withSubmitLock(formEl, fn) {
        setFormDisabled(formEl, true);
        return fn().finally(function() {
            setFormDisabled(formEl, false);
        });
    }

    function handleLoginSubmit(event) {
        event.preventDefault();
        setAlert('', '');
        var email = (byId('authLoginEmail').value || '').trim();
        var password = byId('authLoginPassword').value || '';
        if (!email || !password) {
            setAlert('Email and password are required.', 'warning');
            return;
        }
        withSubmitLock(forms.login, function() {
            return apiJSON('/v1/auth/login', {
                method: 'POST',
                body: JSON.stringify({
                    email: email,
                    password: password
                })
            }).then(function(resp) {
                if (!resp.ok || !resp.data.ok) {
                    setAlert(resp.data.message || 'Unable to sign in.', 'danger');
                    return;
                }
                var nextPath = normalizeReturnPath(qs('return'));
                window.location.href = nextPath || '/';
            }).catch(function() {
                setAlert('Unable to sign in right now.', 'danger');
            });
        });
    }

    function handleRegisterSubmit(event) {
        event.preventDefault();
        setAlert('', '');
        var displayName = (byId('authRegisterName').value || '').trim();
        var email = (byId('authRegisterEmail').value || '').trim();
        var password = byId('authRegisterPassword').value || '';
        var confirmPassword = byId('authRegisterPasswordConfirm').value || '';
        if (!email) {
            setAlert('Email is required.', 'warning');
            return;
        }
        if (password !== confirmPassword) {
            setAlert('Passwords do not match.', 'warning');
            return;
        }
        var issues = passwordIssues(password);
        if (issues.length) {
            setAlert('Password must include ' + issues.join(', ') + '.', 'warning');
            return;
        }
        withSubmitLock(forms.register, function() {
            return apiJSON('/v1/auth/register', {
                method: 'POST',
                body: JSON.stringify({
                    displayName: displayName,
                    email: email,
                    password: password
                })
            }).then(function(resp) {
                if (!resp.ok || !resp.data.ok) {
                    setAlert(resp.data.message || 'Unable to create account.', 'danger');
                    return;
                }
                var message = resp.data.message || 'Registration successful. Please verify your email.';
                var debugUrl = resp.data && resp.data.debug ? resp.data.debug.verificationUrl : '';
                setAlert(appendDebugUrl(message, debugUrl), 'success');
                byId('authLoginEmail').value = email;
                byId('authLoginPassword').value = '';
                showMode('login');
            }).catch(function() {
                setAlert('Unable to register right now.', 'danger');
            });
        });
    }

    function handleResetRequestSubmit(event) {
        event.preventDefault();
        setAlert('', '');
        var email = (byId('authResetEmail').value || '').trim();
        if (!email) {
            setAlert('Email is required.', 'warning');
            return;
        }
        withSubmitLock(forms.resetRequest, function() {
            return apiJSON('/v1/auth/reset/request', {
                method: 'POST',
                body: JSON.stringify({ email: email })
            }).then(function(resp) {
                var message = (resp.data && resp.data.message) ? resp.data.message : 'If your account exists, a reset email has been sent.';
                var debugUrl = resp.data && resp.data.debug ? resp.data.debug.resetUrl : '';
                setAlert(appendDebugUrl(message, debugUrl), 'success');
            }).catch(function() {
                setAlert('Unable to send reset link right now.', 'danger');
            });
        });
    }

    function handleResetConfirmSubmit(event) {
        event.preventDefault();
        setAlert('', '');
        var token = (byId('authResetToken').value || '').trim();
        if (!token) {
            setAlert('Reset token is missing.', 'warning');
            return;
        }
        var password = byId('authResetPassword').value || '';
        var confirmPassword = byId('authResetPasswordConfirm').value || '';
        if (password !== confirmPassword) {
            setAlert('Passwords do not match.', 'warning');
            return;
        }
        var issues = passwordIssues(password);
        if (issues.length) {
            setAlert('Password must include ' + issues.join(', ') + '.', 'warning');
            return;
        }
        withSubmitLock(forms.resetConfirm, function() {
            return apiJSON('/v1/auth/reset/confirm', {
                method: 'POST',
                body: JSON.stringify({
                    token: token,
                    password: password
                })
            }).then(function(resp) {
                if (!resp.ok || !resp.data.ok) {
                    setAlert(resp.data.message || 'Could not reset password.', 'danger');
                    return;
                }
                setAlert(resp.data.message || 'Password updated. You can now sign in.', 'success');
                removeQueryParam('reset_token');
                byId('authResetPassword').value = '';
                byId('authResetPasswordConfirm').value = '';
                showMode('login');
            }).catch(function() {
                setAlert('Unable to reset password right now.', 'danger');
            });
        });
    }

    function bindModeButtons() {
        modeButtons.login.addEventListener('click', function() {
            showMode('login');
        });
        modeButtons.register.addEventListener('click', function() {
            showMode('register');
        });
        modeButtons.reset.addEventListener('click', function() {
            showMode('reset-request');
        });
    }

    function bindFormHandlers() {
        forms.login.addEventListener('submit', handleLoginSubmit);
        forms.register.addEventListener('submit', handleRegisterSubmit);
        forms.resetRequest.addEventListener('submit', handleResetRequestSubmit);
        forms.resetConfirm.addEventListener('submit', handleResetConfirmSubmit);
    }

    function handleVerifyToken() {
        var token = qs('verify_token');
        if (!token) {
            return Promise.resolve();
        }
        return apiJSON('/v1/auth/verify?token=' + encodeURIComponent(token), { method: 'GET' })
            .then(function(resp) {
                if (resp.ok && resp.data.ok) {
                    setAlert(resp.data.message || 'Email verified successfully. You can now sign in.', 'success');
                } else {
                    setAlert((resp.data && resp.data.message) || 'Unable to verify token.', 'danger');
                }
                removeQueryParam('verify_token');
            })
            .catch(function() {
                setAlert('Unable to verify token right now.', 'danger');
            });
    }

    function handleExistingSession() {
        return apiJSON('/v1/auth/me', { method: 'GET' })
            .then(function(resp) {
                var authenticated = !!(resp.data && resp.data.authenticated);
                var container = byId('authSessionState');
                if (!container) {
                    return;
                }
                if (!authenticated) {
                    container.classList.add('d-none');
                    return;
                }
                var user = (resp.data && resp.data.user) ? resp.data.user : {};
                byId('authSessionEmail').textContent = user.email || 'your account';
                container.classList.remove('d-none');
            });
    }

    function bindSessionButtons() {
        var continueBtn = byId('authContinueBtn');
        var logoutBtn = byId('authLogoutBtn');
        var nextPath = normalizeReturnPath(qs('return'));
        continueBtn.addEventListener('click', function() {
            window.location.href = nextPath || '/';
        });
        logoutBtn.addEventListener('click', function() {
            apiJSON('/v1/auth/logout', { method: 'POST' })
                .then(function() {
                    setAlert('You have been signed out.', 'success');
                    byId('authSessionState').classList.add('d-none');
                })
                .catch(function() {
                    setAlert('Unable to sign out right now.', 'danger');
                });
        });
    }

    function boot() {
        bindModeButtons();
        bindFormHandlers();
        bindSessionButtons();

        var resetToken = qs('reset_token');
        if (resetToken) {
            byId('authResetToken').value = resetToken;
        }

        var mode = (qs('mode') || 'login').toLowerCase();
        if (resetToken) {
            showMode('reset-confirm');
        } else if (mode === 'register') {
            showMode('register');
        } else if (mode === 'reset' || mode === 'reset-request') {
            showMode('reset-request');
        } else {
            showMode('login');
        }

        handleVerifyToken().then(function() {
            return handleExistingSession();
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot);
    } else {
        boot();
    }
})();
