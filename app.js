/* ═══════════════════════════════════════
   TaskFlow — App Logic (Mock Data & UI)
   ═══════════════════════════════════════ */

// ─── Sample Data ───
const TEAM = [
  { id: 'babak', name: 'Babak K.', initials: 'BK', role: 'Admin · Full-Stack', color: 'linear-gradient(135deg,#a78bfa,#7c3aed)' },
  { id: 'alice', name: 'Alice M.', initials: 'AM', role: 'Designer', color: 'linear-gradient(135deg,#f472b6,#db2777)' },
  { id: 'omar',  name: 'Omar R.',  initials: 'OR', role: 'Backend Dev', color: 'linear-gradient(135deg,#60a5fa,#2563eb)' },
  { id: 'sofia', name: 'Sofia L.', initials: 'SL', role: 'QA Engineer', color: 'linear-gradient(135deg,#34d399,#059669)' },
];

const PROJECTS = {
  website: { name: 'Website Redesign', color: '#a78bfa' },
  mobile:  { name: 'Mobile App v2',    color: '#60a5fa' },
  api:     { name: 'API Integration',  color: '#34d399' },
};

let tasks = [
  { id: 1,  title: 'Design new landing page hero',           status: 'todo',     priority: 'high',   project: 'website', assignee: 'alice', due: '2026-05-30' },
  { id: 2,  title: 'Set up CI/CD pipeline',                  status: 'todo',     priority: 'medium', project: 'api',     assignee: 'omar',  due: '2026-06-02' },
  { id: 3,  title: 'Write user authentication endpoints',    status: 'todo',     priority: 'urgent', project: 'api',     assignee: 'babak', due: '2026-05-28' },
  { id: 4,  title: 'Create wireframes for settings page',    status: 'todo',     priority: 'low',    project: 'website', assignee: 'alice', due: '2026-06-05' },
  { id: 5,  title: 'Implement push notification service',    status: 'progress', priority: 'high',   project: 'mobile',  assignee: 'omar',  due: '2026-05-29' },
  { id: 6,  title: 'Refactor database models',               status: 'progress', priority: 'medium', project: 'api',     assignee: 'babak', due: '2026-06-01' },
  { id: 7,  title: 'Build onboarding flow screens',          status: 'progress', priority: 'high',   project: 'mobile',  assignee: 'alice', due: '2026-05-31' },
  { id: 8,  title: 'Code review: payment module',            status: 'review',   priority: 'urgent', project: 'website', assignee: 'babak', due: '2026-05-27' },
  { id: 9,  title: 'QA regression test suite',               status: 'review',   priority: 'medium', project: 'mobile',  assignee: 'sofia', due: '2026-06-03' },
  { id: 10, title: 'Deploy staging environment',             status: 'done',     priority: 'medium', project: 'api',     assignee: 'omar',  due: '2026-05-20' },
  { id: 11, title: 'Design system component library',        status: 'done',     priority: 'high',   project: 'website', assignee: 'alice', due: '2026-05-18' },
  { id: 12, title: 'Migrate to Go 1.22',                     status: 'done',     priority: 'low',    project: 'api',     assignee: 'babak', due: '2026-05-15' },
];

const ACTIVITY = [
  { user: 'alice', text: '<strong>Alice M.</strong> completed <strong>Design system component library</strong>', time: '2h ago' },
  { user: 'omar',  text: '<strong>Omar R.</strong> moved <strong>Deploy staging</strong> to Done', time: '3h ago' },
  { user: 'babak', text: '<strong>Babak K.</strong> created <strong>Write user auth endpoints</strong>', time: '5h ago' },
  { user: 'sofia', text: '<strong>Sofia L.</strong> started reviewing <strong>QA regression suite</strong>', time: '6h ago' },
  { user: 'alice', text: '<strong>Alice M.</strong> commented on <strong>Build onboarding flow</strong>', time: '8h ago' },
];

// ─── Helpers ───
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);
const member = (id) => TEAM.find(m => m.id === id);
let nextId = tasks.length + 1;

// ─── Theme ───
const THEME_KEY = 'taskflow-theme';
const THEMES = ['dark', 'light', 'system'];
let currentTheme = 'dark';

const themeIcons = {
  dark: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>',
  light: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="5"/><line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/><line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/></svg>',
  system: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>'
};

function getSystemTheme() {
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function applyTheme(theme) {
  currentTheme = theme;
  localStorage.setItem(THEME_KEY, theme);
  const effective = theme === 'system' ? getSystemTheme() : theme;
  document.documentElement.setAttribute('data-theme', effective);
  const btn = $('#btn-theme');
  if (btn) {
    btn.innerHTML = themeIcons[theme];
    btn.title = 'Theme: ' + theme.charAt(0).toUpperCase() + theme.slice(1);
  }
}

function cycleTheme() {
  const idx = THEMES.indexOf(currentTheme);
  applyTheme(THEMES[(idx + 1) % THEMES.length]);
}

function setupTheme() {
  applyTheme(localStorage.getItem(THEME_KEY) || 'system');
  $('#btn-theme').addEventListener('click', cycleTheme);
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (currentTheme === 'system') {
      document.documentElement.setAttribute('data-theme', getSystemTheme());
    }
  });
}

// ─── Render Kanban Cards ───
function renderKanban() {
  const cols = { todo: $('#cards-todo'), progress: $('#cards-progress'), review: $('#cards-review'), done: $('#cards-done') };
  Object.values(cols).forEach(c => c.innerHTML = '');

  tasks.forEach(t => {
    const m = member(t.assignee);
    const today = new Date().toISOString().slice(0, 10);
    const overdue = t.due < today && t.status !== 'done';

    const card = document.createElement('div');
    card.className = 'task-card';
    card.draggable = true;
    card.dataset.id = t.id;
    card.innerHTML = `
      <div class="card-tags"><span class="card-tag tag-${t.priority}">${t.priority}</span></div>
      <div class="card-title">${t.title}</div>
      <div class="card-meta">
        <div class="card-assignee" style="background:${m.color}" title="${m.name}">${m.initials}</div>
        <span class="card-due ${overdue ? 'overdue' : ''}">${formatDate(t.due)}</span>
      </div>`;
    cols[t.status]?.appendChild(card);

    // Drag events
    card.addEventListener('dragstart', e => { e.dataTransfer.setData('text/plain', t.id); card.classList.add('dragging'); });
    card.addEventListener('dragend', () => card.classList.remove('dragging'));
  });

  // Update counts
  ['todo','progress','review','done'].forEach(s => {
    const count = tasks.filter(t => t.status === s).length;
    const colEl = $(`#col-${s === 'progress' ? 'progress' : s}`);
    if (colEl) colEl.querySelector('.col-count').textContent = count;
  });

  // Update stats
  $('#stat-total').textContent = tasks.length;
  $('#stat-progress').textContent = tasks.filter(t => t.status === 'progress').length;
  $('#stat-done').textContent = tasks.filter(t => t.status === 'done').length;
  const today = new Date().toISOString().slice(0, 10);
  $('#stat-overdue').textContent = tasks.filter(t => t.due < today && t.status !== 'done').length;
}

// ─── Drag & Drop on columns ───
function setupDragDrop() {
  const statusMap = { 'cards-todo': 'todo', 'cards-progress': 'progress', 'cards-review': 'review', 'cards-done': 'done' };
  Object.keys(statusMap).forEach(id => {
    const col = $(`#${id}`);
    col.addEventListener('dragover', e => { e.preventDefault(); col.style.background = 'rgba(167,139,250,.06)'; });
    col.addEventListener('dragleave', () => col.style.background = '');
    col.addEventListener('drop', e => {
      e.preventDefault();
      col.style.background = '';
      const taskId = parseInt(e.dataTransfer.getData('text/plain'));
      const task = tasks.find(t => t.id === taskId);
      if (task) { task.status = statusMap[id]; renderKanban(); renderMyTasks(); }
    });
  });
}

// ─── Format date ───
function formatDate(dateStr) {
  const d = new Date(dateStr + 'T00:00:00');
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

// ─── Render Activity Feed ───
function renderActivity() {
  const feed = $('#activity-feed');
  feed.innerHTML = ACTIVITY.map(a => {
    const m = member(a.user);
    return `<li class="activity-item">
      <div class="activity-avatar" style="background:${m.color}">${m.initials}</div>
      <span class="activity-text">${a.text}</span>
      <span class="activity-time">${a.time}</span>
    </li>`;
  }).join('');
}

// ─── Render My Tasks ───
function renderMyTasks() {
  const list = $('#my-task-list');
  const myTasks = tasks.filter(t => t.assignee === 'babak');
  list.innerHTML = myTasks.map(t => {
    const proj = PROJECTS[t.project];
    return `<li class="task-list-item">
      <span class="tl-col tl-check"><input type="checkbox" ${t.status === 'done' ? 'checked' : ''} data-id="${t.id}" /></span>
      <span class="tl-col tl-title">${t.title}</span>
      <span class="tl-col tl-project" style="color:${proj.color}">${proj.name}</span>
      <span class="tl-col tl-priority priority-${t.priority}">${t.priority.charAt(0).toUpperCase() + t.priority.slice(1)}</span>
      <span class="tl-col tl-due">${formatDate(t.due)}</span>
    </li>`;
  }).join('');

  // Checkbox toggle
  list.querySelectorAll('input[type="checkbox"]').forEach(cb => {
    cb.addEventListener('change', e => {
      const task = tasks.find(t => t.id === parseInt(e.target.dataset.id));
      if (task) { task.status = e.target.checked ? 'done' : 'todo'; renderKanban(); renderMyTasks(); }
    });
  });
}

// ─── Render Team ───
function renderTeam() {
  const grid = $('#team-grid');
  grid.innerHTML = TEAM.map(m => {
    const assigned = tasks.filter(t => t.assignee === m.id);
    const done = assigned.filter(t => t.status === 'done').length;
    const active = assigned.length - done;
    return `<div class="team-member-card">
      <div class="member-avatar" style="background:${m.color}">${m.initials}</div>
      <div class="member-name">${m.name}</div>
      <div class="member-role">${m.role}</div>
      <div class="member-stats">
        <div><span class="member-stat-val">${active}</span><span class="member-stat-lbl">Active</span></div>
        <div><span class="member-stat-val">${done}</span><span class="member-stat-lbl">Done</span></div>
        <div><span class="member-stat-val">${assigned.length}</span><span class="member-stat-lbl">Total</span></div>
      </div>
    </div>`;
  }).join('');
}

// ─── Render Calendar ───
let calYear = 2026, calMonth = 4; // 0-indexed, May = 4
function renderCalendar() {
  const grid = $('#calendar-grid');
  const label = $('#cal-month-label');
  const date = new Date(calYear, calMonth, 1);
  label.textContent = date.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });

  const dayNames = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];
  let html = dayNames.map(d => `<div class="cal-day-name">${d}</div>`).join('');

  const firstDay = date.getDay();
  const daysInMonth = new Date(calYear, calMonth + 1, 0).getDate();
  const prevMonthDays = new Date(calYear, calMonth, 0).getDate();
  const todayStr = new Date().toISOString().slice(0, 10);

  // Previous month days
  for (let i = firstDay - 1; i >= 0; i--) {
    html += `<div class="cal-day other-month">${prevMonthDays - i}</div>`;
  }

  // Current month days
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${calYear}-${String(calMonth + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
    const isToday = dateStr === todayStr;
    const hasTasks = tasks.some(t => t.due === dateStr);
    html += `<div class="cal-day ${isToday ? 'today' : ''}">${d}${hasTasks ? '<span class="cal-dot" style="background:var(--accent-violet)"></span>' : ''}</div>`;
  }

  // Next month days
  const totalCells = firstDay + daysInMonth;
  const remaining = (7 - (totalCells % 7)) % 7;
  for (let i = 1; i <= remaining; i++) {
    html += `<div class="cal-day other-month">${i}</div>`;
  }

  grid.innerHTML = html;
}

// ─── Navigation ───
function setupNav() {
  $$('.nav-item[data-view]').forEach(item => {
    item.addEventListener('click', e => {
      e.preventDefault();
      $$('.nav-item[data-view]').forEach(n => n.classList.remove('active'));
      item.classList.add('active');
      $$('.view').forEach(v => v.classList.remove('active'));
      const view = $(`#view-${item.dataset.view}`);
      if (view) view.classList.add('active');
    });
  });
}

// ─── Modal ───
function setupModal() {
  const overlay = $('#modal-overlay');
  const open = () => overlay.classList.add('open');
  const close = () => overlay.classList.remove('open');

  $('#btn-new-task').addEventListener('click', open);
  $$('.add-card-btn').forEach(btn => btn.addEventListener('click', open));
  $('#modal-close').addEventListener('click', close);
  $('#btn-cancel-task').addEventListener('click', close);
  overlay.addEventListener('click', e => { if (e.target === overlay) close(); });

  $('#new-task-form').addEventListener('submit', e => {
    e.preventDefault();
    const title = $('#task-title').value.trim();
    if (!title) return;

    tasks.push({
      id: nextId++,
      title,
      status: 'todo',
      priority: $('#task-priority').value,
      project: $('#task-project').value,
      assignee: $('#task-assignee').value,
      due: $('#task-due').value || '2026-06-15',
    });

    $('#new-task-form').reset();
    close();
    renderKanban();
    renderMyTasks();
    renderCalendar();
  });
}

// ─── Sidebar Toggle (Mobile) ───
function setupSidebarToggle() {
  $('#menu-toggle').addEventListener('click', () => {
    $('#sidebar').classList.toggle('open');
  });
}

// ─── Calendar Nav ───
function setupCalendarNav() {
  $('#cal-prev').addEventListener('click', () => { calMonth--; if (calMonth < 0) { calMonth = 11; calYear--; } renderCalendar(); });
  $('#cal-next').addEventListener('click', () => { calMonth++; if (calMonth > 11) { calMonth = 0; calYear++; } renderCalendar(); });
}

// ─── Filter Buttons ───
function setupFilters() {
  $$('.filter-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      $$('.filter-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
    });
  });
}

// ─── Init ───
document.addEventListener('DOMContentLoaded', () => {
  setupTheme();
  renderKanban();
  setupDragDrop();
  renderActivity();
  renderMyTasks();
  renderTeam();
  renderCalendar();
  setupNav();
  setupModal();
  setupSidebarToggle();
  setupCalendarNav();
  setupFilters();
});
