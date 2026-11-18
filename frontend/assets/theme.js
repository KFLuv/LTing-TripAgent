(function () {
    'use strict';

    var KEY = 'lting-theme-mode';
    var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

    function getMode() {
        var saved = localStorage.getItem(KEY);
        return saved === 'light' || saved === 'dark' || saved === 'system' ? saved : 'system';
    }

    function resolved(mode) {
        return mode === 'dark' || (mode === 'system' && media && media.matches);
    }

    function apply(mode) {
        var chosen = mode || getMode();
        var dark = resolved(chosen);
        document.documentElement.classList.toggle('theme-dark', dark);
        document.documentElement.dataset.theme = dark ? 'dark' : 'light';
        document.documentElement.dataset.themeMode = chosen;
        document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
        updateButtons(chosen, dark);
        window.dispatchEvent(new CustomEvent('lting:themechange', {
            detail: {mode: chosen, dark: dark}
        }));
    }

    function setMode(mode) {
        var chosen = mode === 'light' || mode === 'dark' || mode === 'system' ? mode : 'system';
        localStorage.setItem(KEY, chosen);
        apply(chosen);
    }

    function cycle() {
        var current = getMode();
        setMode(current === 'light' ? 'dark' : current === 'dark' ? 'system' : 'light');
    }

    function modeLabel(mode, dark) {
        if (mode === 'system') return dark ? '深色 · 跟随系统' : '浅色 · 跟随系统';
        return dark ? '深色模式' : '浅色模式';
    }

    function updateButtons(mode, dark) {
        var label = modeLabel(mode, dark);
        document.querySelectorAll('[data-theme-toggle]').forEach(function (button) {
            button.setAttribute('title', label + '，点击切换');
            button.setAttribute('aria-label', label + '，点击切换');
            var text = button.querySelector('[data-theme-label]');
            if (text) text.textContent = label;
            var glyph = button.querySelector('[data-theme-glyph]');
            if (glyph) glyph.textContent = dark ? '☾' : '☀';
        });
    }

    function mountFallbackButton() {
        if (document.getElementById('nav') || document.querySelector('[data-theme-toggle]')) return;
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'theme-floating';
        button.setAttribute('data-theme-toggle', '');
        button.innerHTML = '<span data-theme-glyph>◐</span><span data-theme-label>主题</span>';
        button.addEventListener('click', cycle);
        document.body.appendChild(button);
        updateButtons(getMode(), resolved(getMode()));
    }

    media && media.addEventListener && media.addEventListener('change', function () {
        if (getMode() === 'system') apply('system');
    });

    window.ShellTheme = {
        getMode: getMode,
        isDark: function () { return resolved(getMode()); },
        setMode: setMode,
        cycle: cycle,
        label: function () { return modeLabel(getMode(), resolved(getMode())); }
    };

    apply(getMode());
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', mountFallbackButton);
    } else {
        mountFallbackButton();
    }
})();
