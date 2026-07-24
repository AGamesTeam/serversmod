package com.andruspro6446.servermod.web;

// Tiny HTML helpers: escaping untrusted text and a shared page skeleton/stylesheet/script so every page
// looks and behaves consistently.
public final class Html
{
    private Html()
    {
    }

    public static String escape(String s)
    {
        if (s == null)
            return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // A small copy-to-clipboard button. `text` is the raw value to copy (will be escaped for the attribute).
    public static String copyButton(String text)
    {
        return "<button type=\"button\" class=\"copy-btn\" onclick=\"copyText(this,'" + escapeJs(text) + "')\" title=\"Copy\">&#128203;</button>";
    }

    private static String escapeJs(String s)
    {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    public static String page(String title, String bodyHtml)
    {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + escape(title) + "</title>"
                + "<style>" + CSS + "</style></head><body><div class=\"wrap\">"
                + "<div class=\"brand\"><span class=\"brand-mark\">&#9899;</span> ServerMod</div>"
                + bodyHtml + "</div><script>" + JS + "</script></body></html>";
    }

    private static final String JS = """
            function copyText(btn, text) {
                navigator.clipboard.writeText(text).then(() => {
                    const original = btn.textContent;
                    btn.textContent = '\\u2705';
                    btn.classList.add('copied');
                    setTimeout(() => { btn.textContent = original; btn.classList.remove('copied'); }, 1200);
                });
            }
            document.querySelectorAll('select[data-toggle]').forEach(sel => {
                const target = document.querySelector(sel.dataset.toggle);
                const amount = document.getElementById('send-amount');
                const sync = () => {
                    const isItem = sel.value === 'item';
                    if (target) target.style.display = isItem ? '' : 'none';
                    if (amount) { amount.step = isItem ? '1' : '0.01'; amount.min = isItem ? '1' : '0.01'; }
                };
                sel.addEventListener('change', sync);
                sync();
            });

            // Shared client-side helpers for pages with a huge, searchable item list (the market table and
            // the buy/sell/send item pickers). Kept dependency-free since the panel has no JS framework.
            const ServerModUI = (() => {
                function debounce(fn, wait) {
                    let t;
                    return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), wait); };
                }

                function el(tag, attrs, children) {
                    const node = document.createElement(tag);
                    for (const [k, v] of Object.entries(attrs || {})) {
                        if (k === 'text') node.textContent = v;
                        else if (k === 'html') node.innerHTML = v;
                        else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
                        else node.setAttribute(k, v);
                    }
                    (children || []).forEach(c => node.appendChild(c));
                    return node;
                }

                async function fetchJson(url) {
                    const res = await fetch(url, { headers: { 'Accept': 'application/json' } });
                    if (!res.ok)
                        throw new Error('request failed: ' + res.status);
                    return res.json();
                }

                function copyBtn(text) {
                    return el('button', { type: 'button', class: 'copy-btn', title: 'Copy',
                        html: '&#128203;', onclick: (e) => copyText(e.currentTarget, text) });
                }

                function badge(item) {
                    return el('span', { class: 'badge ' + item.badgeClass, text: item.badgeText });
                }

                // Wires up a searchable, paginated market table. `root` is the card containing a
                // form#<id>-form (search box), tbody#<id>-rows, and pager#<id>-pager. `mode` is
                // 'admin' (editable price + remove forms) or 'user' (read-only price list).
                function initMarketTable(cfg) {
                    const root = document.querySelector(cfg.root);
                    if (!root) return;
                    const form = root.querySelector('.js-search-form');
                    const input = root.querySelector('.js-search-input');
                    const tbody = root.querySelector('.js-rows');
                    const empty = root.querySelector('.js-empty');
                    const pager = root.querySelector('.js-pager');
                    const count = root.querySelector('.js-count');
                    const table = root.querySelector('table');
                    let currentPage = parseInt(cfg.initialPage || '1', 10);

                    function rowHtml(item) {
                        const tr = el('tr', {});
                        const idTd = el('td', { class: 'item-id' });
                        idTd.appendChild(document.createTextNode(item.id));
                        idTd.appendChild(copyBtn(item.id));
                        tr.appendChild(idTd);

                        const priceTd = el('td', {});
                        priceTd.appendChild(document.createTextNode('$' + item.current.toFixed(2) + ' '));
                        priceTd.appendChild(badge(item));
                        tr.appendChild(priceTd);

                        if (cfg.mode === 'admin') {
                            const baseTd = el('td', {});
                            const priceForm = el('form', { method: 'post', action: '/admin/price', class: 'row-form' });
                            priceForm.appendChild(el('input', { type: 'hidden', name: 'item', value: item.id }));
                            priceForm.appendChild(el('input', { type: 'number', step: '0.01', min: '0', name: 'basePrice', value: item.base.toFixed(2) }));
                            priceForm.appendChild(el('button', { type: 'submit', class: 'btn', text: 'Save' }));
                            baseTd.appendChild(priceForm);
                            tr.appendChild(baseTd);

                            const removeTd = el('td', {});
                            const removeForm = el('form', { method: 'post', action: '/admin/remove-item',
                                onsubmit: (e) => { if (!confirm('Remove ' + item.id + ' from the market?')) e.preventDefault(); } });
                            removeForm.appendChild(el('input', { type: 'hidden', name: 'item', value: item.id }));
                            removeForm.appendChild(el('button', { type: 'submit', class: 'btn danger', text: 'Remove' }));
                            removeTd.appendChild(removeForm);
                            tr.appendChild(removeTd);
                        }
                        return tr;
                    }

                    function renderPager(data) {
                        pager.innerHTML = '';
                        if (data.totalPages <= 1) return;
                        const prev = el('button', { type: 'button', class: 'btn ghost', html: '&lsaquo; Prev' });
                        prev.disabled = data.page <= 1;
                        prev.addEventListener('click', () => load(data.page - 1));
                        const info = el('span', { class: 'page-info', text: 'Page ' + data.page + ' of ' + data.totalPages });
                        const next = el('button', { type: 'button', class: 'btn ghost', html: 'Next &rsaquo;' });
                        next.disabled = data.page >= data.totalPages;
                        next.addEventListener('click', () => load(data.page + 1));
                        pager.appendChild(prev);
                        pager.appendChild(info);
                        pager.appendChild(next);
                    }

                    async function load(page) {
                        currentPage = page;
                        const q = input.value.trim();
                        const url = cfg.endpoint + '?q=' + encodeURIComponent(q) + '&page=' + page;
                        let data;
                        try { data = await fetchJson(url); } catch (e) { return; }

                        tbody.innerHTML = '';
                        data.items.forEach(item => tbody.appendChild(rowHtml(item)));
                        table.style.display = data.items.length ? '' : 'none';
                        empty.style.display = data.items.length ? 'none' : '';
                        if (count) count.textContent = data.total;
                        renderPager(data);

                        const url2 = new URL(window.location.href);
                        url2.searchParams.set('q', q);
                        url2.searchParams.set('page', String(page));
                        window.history.replaceState(null, '', url2);
                        if (cfg.onLoad) cfg.onLoad(data);
                    }

                    if (form) form.addEventListener('submit', (e) => { e.preventDefault(); load(1); });
                    if (input) input.addEventListener('input', debounce(() => load(1), 250));
                    load(currentPage);
                }

                // Turns a plain text input into a type-to-search combobox backed by a market search
                // endpoint, so pickers stay usable even with a huge item list instead of a giant <select>.
                function initItemPicker(input, endpoint) {
                    if (!input) return;
                    const list = el('div', { class: 'combo-list' });
                    list.style.display = 'none';
                    input.insertAdjacentElement('afterend', list);
                    input.setAttribute('autocomplete', 'off');
                    input.classList.add('combo-input');

                    function hide() { list.style.display = 'none'; list.innerHTML = ''; }

                    async function search() {
                        const q = input.value.trim();
                        let data;
                        try { data = await fetchJson(endpoint + '?q=' + encodeURIComponent(q) + '&page=1&pageSize=8'); }
                        catch (e) { return; }

                        list.innerHTML = '';
                        if (!data.items.length) {
                            list.appendChild(el('div', { class: 'combo-empty', text: q ? 'No matching items' : 'Type to search items...' }));
                        } else {
                            data.items.forEach(item => {
                                const row = el('div', { class: 'combo-item' });
                                row.appendChild(el('span', { class: 'item-id', text: item.id }));
                                row.appendChild(el('span', { class: 'combo-price', text: '$' + item.current.toFixed(2) }));
                                row.addEventListener('mousedown', (e) => {
                                    e.preventDefault();
                                    input.value = item.id;
                                    hide();
                                    input.dispatchEvent(new Event('change'));
                                });
                                list.appendChild(row);
                            });
                        }
                        list.style.display = '';
                    }

                    input.addEventListener('focus', search);
                    input.addEventListener('input', debounce(search, 200));
                    input.addEventListener('blur', () => setTimeout(hide, 120));
                    input.addEventListener('keydown', (e) => { if (e.key === 'Escape') hide(); });
                }

                return { initMarketTable, initItemPicker };
            })();
            """;

    private static final String CSS = """
            :root{color-scheme:dark;}
            *{box-sizing:border-box;}
            body{margin:0;background:radial-gradient(1200px 600px at 50% -10%,#1d2030,#101218);color:#e7e9ee;
                font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;}
            .wrap{max-width:780px;margin:0 auto;padding:28px 20px 60px;}
            .brand{display:flex;align-items:center;gap:8px;font-weight:700;letter-spacing:.02em;color:#9aa0ad;
                font-size:.9em;text-transform:uppercase;margin-bottom:18px;}
            .brand-mark{color:#7c8cff;font-size:1.1em;}
            h1{font-size:1.7em;margin:0 0 4px;letter-spacing:-.01em;}
            h2{font-size:1.1em;margin:0 0 12px;color:#c9cdd8;display:flex;align-items:center;gap:8px;}
            p.sub{color:#9aa0ad;margin:2px 0 0;line-height:1.5;}
            .card{background:linear-gradient(180deg,#1d212b,#191c24);border:1px solid #2a2e39;border-radius:14px;
                padding:22px;margin-bottom:20px;box-shadow:0 8px 24px -12px rgba(0,0,0,.5);}
            .lock{font-size:2.6em;text-align:center;margin-bottom:6px;filter:drop-shadow(0 4px 12px rgba(124,140,255,.35));}
            label{display:block;font-size:.82em;color:#9aa0ad;margin:14px 0 5px;font-weight:600;}
            input[type=text],input[type=number],input[type=password],select{
                width:100%;padding:10px 12px;border-radius:8px;border:1px solid #333747;background:#12141a;
                color:#e7e9ee;font-size:1em;transition:border-color .15s,box-shadow .15s;}
            input[type=text]:focus,input[type=number]:focus,input[type=password]:focus,select:focus{
                outline:none;border-color:#7c8cff;box-shadow:0 0 0 3px rgba(124,140,255,.18);}
            input[type=submit],button.btn{
                margin-top:16px;padding:10px 18px;border-radius:8px;border:none;
                background:linear-gradient(135deg,#7c8cff,#5a67d8);color:#fff;font-weight:600;
                font-size:.95em;cursor:pointer;transition:transform .1s,filter .15s;}
            input[type=submit]:hover,button.btn:hover{filter:brightness(1.1);}
            input[type=submit]:active,button.btn:active{transform:translateY(1px);}
            button.btn.danger{background:linear-gradient(135deg,#f56565,#c53030);}
            button.btn.ghost{background:transparent;border:1px solid #333747;color:#c9cdd8;box-shadow:none;}
            .copy-btn{background:#12141a;border:1px solid #333747;border-radius:6px;color:#c9cdd8;cursor:pointer;
                padding:4px 8px;font-size:.9em;margin-left:6px;transition:background .15s;}
            .copy-btn:hover{background:#20232c;}
            .copy-btn.copied{border-color:#3aa76d;}
            table{width:100%;border-collapse:collapse;margin-top:4px;}
            th,td{text-align:left;padding:10px 8px;border-bottom:1px solid #262a34;font-size:.92em;}
            th{color:#8890a0;font-weight:600;font-size:.78em;text-transform:uppercase;letter-spacing:.04em;}
            tbody tr{transition:background .12s;}
            tbody tr:hover{background:#20232c;}
            .item-id{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:.88em;color:#c9cdd8;}
            .row-form{display:flex;gap:8px;align-items:center;margin-top:0;}
            .row-form input{width:auto;flex:1;margin-top:0;}
            .row-form input[type=submit],.row-form button{margin-top:0;flex:none;padding:8px 14px;font-size:.88em;}
            .badge{display:inline-block;padding:2px 8px;border-radius:999px;font-size:.78em;font-weight:700;}
            .badge.up{background:#173a24;color:#8fe3ab;}
            .badge.down{background:#3a1e20;color:#ff9b9e;}
            .badge.flat{background:#23262f;color:#9aa0ad;}
            .msg{padding:12px 16px;border-radius:10px;margin-bottom:18px;font-size:.92em;display:flex;gap:8px;align-items:center;}
            .msg.error{background:#3a1e20;color:#ff9b9e;border:1px solid #5c2b2f;}
            .msg.ok{background:#173a24;color:#8fe3ab;border:1px solid #245e39;}
            .balance{font-size:2.1em;font-weight:800;color:#8fe3ab;letter-spacing:-.01em;}
            a{color:#93a4ff;text-decoration:none;}
            a:hover{text-decoration:underline;}
            .topbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;gap:12px;flex-wrap:wrap;}
            .topbar a{font-size:.85em;color:#9aa0ad;}
            .topbar-links{display:flex;gap:14px;align-items:center;flex-wrap:wrap;}
            .hint{color:#767c8a;font-size:.82em;margin-top:6px;}
            code{background:#12141a;padding:2px 6px;border-radius:5px;font-size:.9em;}
            .count-pill{background:#23262f;color:#9aa0ad;border-radius:999px;padding:1px 9px;font-size:.68em;
                font-weight:700;text-transform:none;letter-spacing:0;margin-left:2px;}
            .search-bar{margin-bottom:2px;}
            .search-bar input{margin-top:0;}
            .empty-row{color:#767c8a;font-size:.9em;text-align:center;padding:22px 8px;}
            .pager{display:flex;align-items:center;justify-content:center;gap:14px;margin-top:14px;}
            .pager .btn.ghost{margin-top:0;padding:7px 14px;font-size:.85em;}
            .pager .btn.ghost:disabled{opacity:.35;cursor:default;}
            .pager a.btn.ghost{display:inline-block;text-decoration:none;}
            .page-info{color:#9aa0ad;font-size:.85em;}
            .combo{position:relative;}
            .combo-input{position:relative;z-index:1;}
            .combo-list{position:absolute;left:0;right:0;top:calc(100% + 4px);z-index:20;background:#191c24;
                border:1px solid #333747;border-radius:10px;box-shadow:0 12px 28px -10px rgba(0,0,0,.6);
                max-height:220px;overflow-y:auto;padding:4px;}
            .combo-item{display:flex;justify-content:space-between;gap:10px;padding:8px 10px;border-radius:7px;
                cursor:pointer;font-size:.9em;}
            .combo-item:hover{background:#242832;}
            .combo-item .item-id{color:#e7e9ee;}
            .combo-price{color:#8fe3ab;white-space:nowrap;}
            .combo-empty{padding:8px 10px;color:#767c8a;font-size:.85em;}
            """;
}
