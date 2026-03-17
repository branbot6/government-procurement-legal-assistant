const state = {
  token: localStorage.getItem('ui_token') || '',
  me: null,
  authConfig: { inviteEnabled: true },
  projects: [],
  projectConversations: {},
  conversationDetails: {},
  conversationIndexRows: [],
  currentProjectId: '',
  currentConversationId: '',
  conversationLoadVersion: 0,
  askPending: false,
  scopeProjectId: '',
  projectQuery: '',
  projectCollapsed: JSON.parse(localStorage.getItem('ui_project_collapsed') || '{}'),
  railCollapsed: localStorage.getItem('ui_rail_collapsed') === '1',
  railWidth: Number(localStorage.getItem('ui_rail_width') || 286),
  expandedAssistantMessageKeys: new Set(),
  mode: localStorage.getItem('ui_mode') || 'quick'
};

const DEFAULT_PROJECT_NAME = '未分类';
const DEFAULT_PROJECT_DESC = '系统自动创建，用于直接对话';
const ASSISTANT_COLLAPSE_LINES = 5;

const $ = (id) => document.getElementById(id);

function toast(msg) {
  const t = $('toast');
  if (!t) return;
  t.textContent = msg;
  t.classList.remove('hidden');
  setTimeout(() => t.classList.add('hidden'), 2300);
}

function debounce(fn, waitMs = 120) {
  let timer = null;
  return (...args) => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => fn(...args), waitMs);
  };
}

function cancelPendingConversationLoad() {
  state.conversationLoadVersion += 1;
}

function setAskPending(pending) {
  const askBtn = $('askBtn');
  if (!askBtn) return;
  askBtn.disabled = pending;
  askBtn.textContent = pending ? '思考中...' : '发送并保存';
}

async function api(path, options = {}) {
  const headers = options.headers || {};
  if (state.token) headers['X-Auth-Token'] = state.token;
  if (!(options.body instanceof FormData)) headers['Content-Type'] = headers['Content-Type'] || 'application/json';

  const res = await fetch(path, { ...options, headers });
  if (!res.ok) {
    let err = `HTTP ${res.status}`;
    try { err = (await res.json()).error || err; } catch (_) {}
    throw new Error(err);
  }
  if (res.status === 204) return null;
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) return res.json();
  return res.blob();
}

function applyRailState() {
  const app = $('app');
  if (!app) return;
  app.style.setProperty('--rail-width', `${Math.min(420, Math.max(220, state.railWidth))}px`);
  app.classList.toggle('rail-collapsed', state.railCollapsed);
}

function setTheme(theme) {
  document.body.classList.toggle('dark', theme === 'dark');
  localStorage.setItem('ui_theme', theme);
}

function modeLabel(mode) {
  return mode === 'compat' ? '兼容' : '快速';
}

function applyModeUI() {
  const text = `模式：${modeLabel(state.mode)}`;
  const topBtn = $('themeToggle');
  const drawerBtn = $('drawerThemeBtn');
  if (topBtn) topBtn.textContent = text;
  if (drawerBtn) drawerBtn.textContent = text;
}

function toggleMode() {
  state.mode = state.mode === 'quick' ? 'compat' : 'quick';
  localStorage.setItem('ui_mode', state.mode);
  applyModeUI();
  toast(`已切换到${modeLabel(state.mode)}模式`);
}

function saveProjectCollapsed() {
  localStorage.setItem('ui_project_collapsed', JSON.stringify(state.projectCollapsed));
}

function toggleDrawer(show) {
  const drawer = $('userDrawer');
  if (!drawer) return;
  drawer.classList.toggle('hidden', !show);
  if (show && state.me) {
    $('drawerUsername').textContent = state.me.username || '-';
    $('drawerHint').textContent = state.me.passwordHint || '-';
    refreshInviteAdmin().catch(() => {});
  }
}

function applyAuthConfig() {
  const wrap = $('registerInviteWrap');
  if (!wrap) return;
  const enabled = !!state.authConfig?.inviteEnabled;
  wrap.classList.toggle('hidden', !enabled);
  if (!enabled) {
    const input = $('registerInviteCode');
    if (input) input.value = '';
  }
}

function bindPasswordToggle(toggleId, inputId) {
  const toggle = $(toggleId);
  const input = $(inputId);
  if (!toggle || !input) return;
  toggle.addEventListener('click', () => {
    const visible = input.type === 'text';
    input.type = visible ? 'password' : 'text';
    toggle.textContent = visible ? '显示' : '隐藏';
    toggle.setAttribute('aria-label', visible ? '显示密码' : '隐藏密码');
  });
}

function switchView(loggedIn) {
  $('authView')?.classList.toggle('hidden', loggedIn);
  $('workspaceView')?.classList.toggle('hidden', !loggedIn);
  $('leftRail')?.classList.toggle('hidden', !loggedIn);
  $('railResize')?.classList.toggle('hidden', !loggedIn || state.railCollapsed);
  const meLabel = $('meLabel');
  if (meLabel) meLabel.textContent = loggedIn && state.me ? `账号: ${state.me.username}` : '';
  if (!loggedIn) toggleDrawer(false);
}

async function login() {
  const payload = {
    username: $('loginUsername').value.trim(),
    password: $('loginPassword').value
  };
  const res = await api('/api/v1/ui/auth/login', { method: 'POST', body: JSON.stringify(payload) });
  state.token = res.token;
  localStorage.setItem('ui_token', state.token);
  await loadMe();
  await refreshAll();
  switchView(true);
}

async function register() {
  const payload = {
    username: $('registerUsername').value.trim(),
    password: $('registerPassword').value,
    passwordHint: $('registerHint').value.trim(),
    inviteCode: state.authConfig?.inviteEnabled ? $('registerInviteCode').value.trim() : ''
  };
  const res = await api('/api/v1/ui/auth/register', { method: 'POST', body: JSON.stringify(payload) });
  state.token = res.token;
  localStorage.setItem('ui_token', state.token);
  await loadMe();
  await refreshAll();
  switchView(true);
}

async function loadMe() {
  state.me = await api('/api/v1/ui/auth/me');
}

async function loadAuthConfig() {
  try {
    state.authConfig = await api('/api/v1/ui/auth/config');
  } catch (_) {
    state.authConfig = { inviteEnabled: true };
  }
  applyAuthConfig();
}

function logout() {
  state.token = '';
  state.me = null;
  state.projects = [];
  state.projectConversations = {};
  state.conversationDetails = {};
  state.conversationIndexRows = [];
  state.currentProjectId = '';
  state.currentConversationId = '';
  state.conversationLoadVersion = 0;
  state.askPending = false;
  state.scopeProjectId = '';
  state.projectCollapsed = {};
  state.expandedAssistantMessageKeys = new Set();
  localStorage.removeItem('ui_token');
  setAskPending(false);
  switchView(false);
}

async function fetchHint() {
  const username = $('loginUsername').value.trim();
  if (!username) return toast('请先输入账号');
  const q = new URLSearchParams({ username });
  const res = await api(`/api/v1/ui/auth/hint?${q.toString()}`);
  $('hintText').textContent = `提示: ${res.passwordHint}`;
}

function fmtDateTime(ts) {
  if (!ts) return '-';
  try {
    return new Date(ts).toLocaleString();
  } catch (_) {
    return '-';
  }
}

function renderInviteList(items = []) {
  const box = $('inviteList');
  if (!box) return;
  if (!items.length) {
    box.innerHTML = '<div class="meta">暂无邀请码</div>';
    return;
  }
  box.innerHTML = '';
  const frag = document.createDocumentFragment();
  items.forEach((item) => {
    const div = document.createElement('div');
    div.className = 'invite-item';
    div.innerHTML = `
      <div class="code">${item.code}</div>
      <div class="meta">状态: ${item.active ? '可用' : '停用'} · 使用人: ${item.usedBy || '-'} · 使用时间: ${fmtDateTime(item.usedAt)}</div>
      <div class="ops"></div>
    `;
    const ops = div.querySelector('.ops');
    if (item.active && !item.usedAt) {
      const btn = document.createElement('button');
      btn.className = 'btn btn-ghost small';
      btn.textContent = '停用';
      btn.onclick = () => deactivateInvite(item.code).catch((e) => toast(e.message));
      ops.appendChild(btn);
    }
    frag.appendChild(div);
  });
  box.appendChild(frag);
}

async function refreshInviteAdmin() {
  const panel = $('inviteAdmin');
  if (!panel) return;
  const isAdmin = !!state.me?.admin;
  panel.classList.toggle('hidden', !isAdmin);
  if (!isAdmin) return;
  const res = await api('/api/v1/ui/admin/invites?limit=200');
  $('inviteTotal').textContent = String(res.total || 0);
  $('inviteActive').textContent = String(res.active || 0);
  $('inviteUsed').textContent = String(res.used || 0);
  renderInviteList(res.items || []);
}

async function importInvites() {
  const input = $('inviteImportInput');
  if (!input) return;
  const raw = input.value || '';
  const codes = raw.split(/\r?\n|,|\s+/).map((x) => x.trim()).filter(Boolean);
  if (!codes.length) return toast('请输入邀请码');
  const res = await api('/api/v1/ui/admin/invites/import', {
    method: 'POST',
    body: JSON.stringify({ codes })
  });
  input.value = '';
  toast(`导入完成：新增 ${res.imported}，跳过 ${res.skipped}`);
  await refreshInviteAdmin();
}

async function createRandomInvites() {
  const count = Number($('inviteRandomCount')?.value || 20);
  const length = Number($('inviteRandomLength')?.value || 9);
  const res = await api('/api/v1/ui/admin/invites/create-random', {
    method: 'POST',
    body: JSON.stringify({ count, length })
  });
  toast(`生成完成：新增 ${res.imported}，跳过 ${res.skipped}`);
  await refreshInviteAdmin();
}

async function deactivateInvite(code) {
  await api(`/api/v1/ui/admin/invites/deactivate/${encodeURIComponent(code)}`, { method: 'POST' });
  toast('已停用邀请码');
  await refreshInviteAdmin();
}

async function ensureProjectAvailable() {
  if (state.projects.length > 0) return;
  const created = await api('/api/v1/ui/projects', {
    method: 'POST',
    body: JSON.stringify({ name: DEFAULT_PROJECT_NAME, description: DEFAULT_PROJECT_DESC })
  });
  state.currentProjectId = created.id;
}

async function refreshProjects() {
  state.projects = await api('/api/v1/ui/projects');
  const ids = new Set(state.projects.map((p) => p.id));
  Object.keys(state.projectCollapsed).forEach((id) => {
    if (!ids.has(id)) delete state.projectCollapsed[id];
  });
  if (state.scopeProjectId && !ids.has(state.scopeProjectId)) {
    state.scopeProjectId = '';
  }
  saveProjectCollapsed();
  if (!state.projects.some((p) => p.id === state.currentProjectId)) {
    state.currentProjectId = state.projects[0]?.id || '';
    state.currentConversationId = '';
  }
}

async function refreshProjectTreeData() {
  const snapshot = await api('/api/v1/ui/conversations/snapshot');
  const byProject = {};
  const byConversation = {};
  snapshot.forEach((item) => {
    if (!byProject[item.projectId]) byProject[item.projectId] = [];
    byProject[item.projectId].push({
      id: item.conversationId,
      title: item.title,
      updatedAt: item.updatedAt,
      messageCount: item.messageCount
    });
    byConversation[item.conversationId] = {
      id: item.conversationId,
      title: item.title,
      updatedAt: item.updatedAt,
      projectId: item.projectId,
      messages: item.messages || []
    };
  });
  state.projectConversations = byProject;
  state.conversationDetails = byConversation;
  rebuildConversationIndex();
}

function rebuildConversationIndex() {
  const rows = [];
  state.projects.forEach((project) => {
    (state.projectConversations[project.id] || []).forEach((conv) => {
      rows.push({
        projectId: project.id,
        projectName: project.name,
        conversationId: conv.id,
        title: conv.title,
        updatedAt: conv.updatedAt || 0,
        messageCount: conv.messageCount || 0
      });
    });
  });
  rows.sort((a, b) => b.updatedAt - a.updatedAt);
  state.conversationIndexRows = rows;
}

function ensureProjectBucket(projectId) {
  if (!state.projectConversations[projectId]) {
    state.projectConversations[projectId] = [];
  }
}

function upsertConversationSummary(projectId, summary) {
  ensureProjectBucket(projectId);
  const idx = state.projectConversations[projectId].findIndex((c) => c.id === summary.id);
  if (idx >= 0) {
    state.projectConversations[projectId][idx] = { ...state.projectConversations[projectId][idx], ...summary };
  } else {
    state.projectConversations[projectId].push(summary);
  }
}

function removeConversationSummary(projectId, conversationId) {
  ensureProjectBucket(projectId);
  state.projectConversations[projectId] = state.projectConversations[projectId]
    .filter((c) => c.id !== conversationId);
}

function touchProjectUpdatedAt(projectId, ts) {
  const p = state.projects.find((x) => x.id === projectId);
  if (p) p.updatedAt = Math.max(p.updatedAt || 0, ts || Date.now());
}

function filteredProjects() {
  const q = state.projectQuery.trim().toLowerCase();
  if (!q) return state.projects;
  if (q.startsWith('project:')) {
    const v = q.replace('project:', '').trim();
    return state.projects.filter((p) => p.name.toLowerCase().includes(v));
  }
  if (q.startsWith('desc:')) {
    const v = q.replace('desc:', '').trim();
    return state.projects.filter((p) => (p.description || '').toLowerCase().includes(v));
  }
  if (q === 'has:chat') {
    return state.projects.filter((p) => (p.conversationCount || 0) > 0);
  }
  return state.projects.filter((p) => (`${p.name} ${p.description || ''}`).toLowerCase().includes(q));
}

function projectLastActiveAt(project) {
  const convs = state.projectConversations[project.id] || [];
  const latestConv = convs.reduce((max, c) => Math.max(max, c.updatedAt || 0), 0);
  return Math.max(latestConv, project.updatedAt || 0);
}

function sortedProjectsForTree() {
  const projects = [...filteredProjects()];
  const uncategorized = projects.find((p) => p.name === DEFAULT_PROJECT_NAME);
  const others = projects
    .filter((p) => p.id !== uncategorized?.id)
    .sort((a, b) => projectLastActiveAt(b) - projectLastActiveAt(a));
  return uncategorized ? [uncategorized, ...others] : others;
}

function sortedConversationsForProject(projectId) {
  return [...(state.projectConversations[projectId] || [])]
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}

function flattenedConversations() {
  return state.conversationIndexRows;
}

function updateScopeBadge() {
  const badge = $('scopeBadge');
  if (!badge) return;
  if (!state.scopeProjectId) {
    badge.textContent = '全部';
    return;
  }
  const p = state.projects.find((x) => x.id === state.scopeProjectId);
  badge.textContent = p ? p.name : '全部';
}

function setChatIdle(projectName = '') {
  state.expandedAssistantMessageKeys = new Set();
  $('chatTitle').textContent = '新对话';
  $('chatSubtitle').textContent = projectName ? `项目: ${projectName}` : '可直接输入开始聊天，系统会自动保存';
  renderMessages([]);
}

function renderProjectTree() {
  const ul = $('projectFolders');
  if (!ul) return;
  ul.innerHTML = '';
  updateScopeBadge();
  const frag = document.createDocumentFragment();

  sortedProjectsForTree().forEach((project) => {
    const li = document.createElement('li');
    li.className = `project-node ${project.id === state.scopeProjectId ? 'active' : ''}`;
    li.dataset.projectId = project.id;
    const isCollapsed = state.projectCollapsed[project.id] === true;

    const row = document.createElement('div');
    row.className = 'project-row';
    row.innerHTML = `<div class="project-row-left"><button class="project-toggle" type="button" aria-label="折叠项目">${isCollapsed ? '▸' : '▾'}</button><strong>${project.name}</strong></div><span class="project-count">${(state.projectConversations[project.id] || []).length}</span>`;
    row.addEventListener('click', () => {
      cancelPendingConversationLoad();
      state.scopeProjectId = project.id;
      renderProjectTree();
      renderConversationFeed();
    });
    const toggle = row.querySelector('.project-toggle');
    toggle?.addEventListener('click', (e) => {
      e.stopPropagation();
      state.projectCollapsed[project.id] = !isCollapsed;
      saveProjectCollapsed();
      renderProjectTree();
    });

    const desc = document.createElement('div');
    desc.className = 'project-desc meta';
    desc.textContent = project.description || '无描述';
    if (isCollapsed) desc.classList.add('hidden');

    li.addEventListener('dragover', (e) => {
      e.preventDefault();
      li.classList.add('drop-target');
    });
    li.addEventListener('dragleave', () => {
      li.classList.remove('drop-target');
    });
    li.addEventListener('drop', async (e) => {
      e.preventDefault();
      li.classList.remove('drop-target');
      const raw = e.dataTransfer?.getData('application/x-ui-conversation') || e.dataTransfer?.getData('text/plain') || '';
      if (!raw) return;
      try {
        const payload = JSON.parse(raw);
        await moveConversationToProject(payload, project.id);
      } catch (err) {
        toast(err.message || '拖拽移动失败');
      }
    });

    li.appendChild(row);
    li.appendChild(desc);
    frag.appendChild(li);
  });
  ul.appendChild(frag);
}

function renderConversationFeed() {
  const ul = $('conversationFeed');
  if (!ul) return;
  ul.innerHTML = '';
  const frag = document.createDocumentFragment();

  const q = state.projectQuery.trim().toLowerCase();
  const rows = flattenedConversations()
    .filter((row) => !state.scopeProjectId || row.projectId === state.scopeProjectId)
    .filter((row) => {
      if (!q) return true;
      if (q.startsWith('project:') || q.startsWith('desc:') || q === 'has:chat') return true;
      return (`${row.title} ${row.projectName}`).toLowerCase().includes(q);
    });

  rows.forEach((row) => {
    const li = document.createElement('li');
    li.className = row.conversationId === state.currentConversationId ? 'active' : '';
    li.draggable = true;
    li.innerHTML = `<strong>${row.title}</strong><div class="meta">${row.projectName} · ${row.messageCount} 消息</div>`;
    li.addEventListener('click', () => {
      loadConversation(row.projectId, row.conversationId).catch((err) => toast(err.message));
    });
    li.addEventListener('dragstart', (e) => {
      const payload = JSON.stringify({ conversationId: row.conversationId, sourceProjectId: row.projectId });
      e.dataTransfer?.setData('application/x-ui-conversation', payload);
      e.dataTransfer?.setData('text/plain', payload);
      if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
    });
    frag.appendChild(li);
  });
  ul.appendChild(frag);
}

async function moveConversationToProject(payload, targetProjectId) {
  if (!payload?.conversationId || !targetProjectId) return;
  if (payload.sourceProjectId === targetProjectId) {
    toast('该对话已在当前项目');
    return;
  }

  await api(`/api/v1/ui/conversations/${payload.conversationId}/move`, {
    method: 'POST',
    body: JSON.stringify({ targetProjectId })
  });

  const sourceProjectId = payload.sourceProjectId;
  const now = Date.now();
  let movedSummary = null;
  if (sourceProjectId) {
    movedSummary = (state.projectConversations[sourceProjectId] || [])
      .find((c) => c.id === payload.conversationId) || null;
    removeConversationSummary(sourceProjectId, payload.conversationId);
    touchProjectUpdatedAt(sourceProjectId, now);
  }
  if (!movedSummary) {
    movedSummary = {
      id: payload.conversationId,
      title: state.conversationDetails[payload.conversationId]?.title || '对话',
      updatedAt: now,
      messageCount: state.conversationDetails[payload.conversationId]?.messages?.length || 0
    };
  }
  movedSummary.updatedAt = now;
  upsertConversationSummary(targetProjectId, movedSummary);
  if (state.conversationDetails[payload.conversationId]) {
    state.conversationDetails[payload.conversationId].projectId = targetProjectId;
    state.conversationDetails[payload.conversationId].updatedAt = now;
  }
  touchProjectUpdatedAt(targetProjectId, now);
  rebuildConversationIndex();
  await refreshProjects();

  if (state.currentConversationId === payload.conversationId) {
    state.currentProjectId = targetProjectId;
    state.scopeProjectId = targetProjectId;
    renderProjectTree();
    renderConversationFeed();
  } else {
    renderProjectTree();
    renderConversationFeed();
  }
  toast('已移动到目标项目');
}

function buildConversationTitle(question) {
  const compact = question.replace(/\s+/g, ' ').trim();
  if (!compact) return '新对话';
  return compact.length > 22 ? `${compact.slice(0, 22)}...` : compact;
}

async function createProject() {
  const payload = {
    name: $('newProjectName').value.trim(),
    description: $('newProjectDesc').value.trim()
  };
  if (!payload.name) return toast('请输入项目名');
  const created = await api('/api/v1/ui/projects', { method: 'POST', body: JSON.stringify(payload) });
  $('newProjectName').value = '';
  $('newProjectDesc').value = '';
  state.currentProjectId = created.id;
  state.currentConversationId = '';
  state.scopeProjectId = created.id;
  state.projectCollapsed[created.id] = false;
  saveProjectCollapsed();
  await refreshProjects();
  await refreshProjectTreeData();
  renderProjectTree();
  renderConversationFeed();
  setChatIdle(created.name);
}

async function newChat() {
  cancelPendingConversationLoad();
  if (!state.currentProjectId && !state.scopeProjectId) {
    await ensureProjectAvailable();
    await refreshProjects();
    await refreshProjectTreeData();
  }
  const targetProjectId = state.scopeProjectId || state.currentProjectId;
  if (targetProjectId) {
    state.currentProjectId = targetProjectId;
  }
  state.currentConversationId = '';
  if (targetProjectId) {
    state.projectCollapsed[targetProjectId] = false;
    saveProjectCollapsed();
  }
  const current = state.projects.find((p) => p.id === targetProjectId);
  renderProjectTree();
  renderConversationFeed();
  setChatIdle(current?.name || '');
}

async function loadConversation(projectId, conversationId) {
  const loadVersion = ++state.conversationLoadVersion;
  state.expandedAssistantMessageKeys = new Set();
  let c = state.conversationDetails[conversationId];
  if (!c) {
    c = await api(`/api/v1/ui/projects/${projectId}/conversations/${conversationId}`);
    if (loadVersion !== state.conversationLoadVersion) {
      return;
    }
    state.conversationDetails[conversationId] = {
      id: c.id,
      title: c.title,
      updatedAt: c.updatedAt,
      projectId,
      messages: c.messages || []
    };
  }
  state.currentProjectId = projectId;
  state.scopeProjectId = projectId;
  state.currentConversationId = c.id;
  state.projectCollapsed[projectId] = false;
  saveProjectCollapsed();
  $('chatTitle').textContent = c.title;
  $('chatSubtitle').textContent = `最近更新: ${new Date(c.updatedAt).toLocaleString()}`;
  renderProjectTree();
  renderConversationFeed();
  renderMessages(c.messages);
}

function renderMessages(messages) {
  const box = $('messages');
  if (!box) return;
  box.innerHTML = '';

  const roleLabel = (role) => {
    if (role === 'assistant') return '布兰智能';
    if (role === 'user') return state.me?.username || '用户';
    return role || '消息';
  };

  messages.forEach((m) => {
    const div = document.createElement('div');
    if (m.typing) {
      div.className = 'msg assistant typing';
      div.innerHTML = `<div class="msg-content"><strong class="msg-label">${roleLabel('assistant')}:</strong></div><div class="typing-dots" aria-label="模型正在思考" role="status"><span></span><span></span><span></span></div>`;
    } else {
      div.className = `msg ${m.role}`;
      const label = roleLabel(m.role);
      const safeContent = m.content == null ? '' : String(m.content);
      const normalizedContent = safeContent
        .replace(/\r\n/g, '\n')
        .replace(/^[\s\r\n]+/, '')
        .replace(/\n[ \t]*\n[ \t]*\n+/g, '\n\n');

      if (m.role === 'assistant') {
        const msgKey = assistantMessageKey(m);
        const lines = normalizedContent.split('\n');
        const attachmentLinks = extractFastAttachmentLinks(normalizedContent);
        const canCollapse = lines.length > ASSISTANT_COLLAPSE_LINES;
        const expanded = !canCollapse || state.expandedAssistantMessageKeys.has(msgKey);
        const visibleText = expanded
          ? normalizedContent
          : `${lines.slice(0, ASSISTANT_COLLAPSE_LINES).join('\n')}\n...`;

        div.classList.toggle('collapsed', canCollapse && !expanded);
        div.innerHTML = `<div class="msg-content"><strong class="msg-label">${escapeHtml(label)}:</strong> ${renderAssistantRichText(visibleText)}</div>`;
        if (attachmentLinks.length) {
          const linksWrap = document.createElement('div');
          linksWrap.className = 'attachment-links';
          attachmentLinks.forEach((lnk) => {
            const a = document.createElement('a');
            a.className = 'msg-link';
            a.href = lnk.href;
            a.setAttribute('download', '');
            a.textContent = `下载：${lnk.label}`;
            linksWrap.appendChild(a);
          });
          div.appendChild(linksWrap);
        }
        if (canCollapse) {
          const toggleBtn = document.createElement('button');
          toggleBtn.className = 'msg-toggle btn btn-ghost small';
          toggleBtn.type = 'button';
          toggleBtn.textContent = expanded ? '收起' : '展开';
          toggleBtn.onclick = () => {
            if (state.expandedAssistantMessageKeys.has(msgKey)) {
              state.expandedAssistantMessageKeys.delete(msgKey);
            } else {
              state.expandedAssistantMessageKeys.add(msgKey);
            }
            renderMessages(messages);
          };
          div.appendChild(toggleBtn);
        }
      } else {
        div.innerHTML = `<div class="msg-content"><strong class="msg-label">${escapeHtml(label)}:</strong> ${escapeHtml(normalizedContent)}</div>`;
      }
    }
    box.appendChild(div);
  });
  box.scrollTop = box.scrollHeight;
  updateSupportHint(messages);
}

function assistantMessageKey(message) {
  if (message?.id) return String(message.id);
  return `${message?.role || 'assistant'}-${message?.createdAt || 0}-${String(message?.content || '').length}`;
}

function updateSupportHint(messages = null) {
  const hint = $('supportHint');
  const input = $('questionInput');
  if (!hint || !input) return;

  const hasMessages = Array.isArray(messages)
    ? messages.length > 0
    : ((state.conversationDetails[state.currentConversationId]?.messages || []).length > 0);
  const hasDraft = input.value.trim().length > 0;
  hint.classList.toggle('hidden', hasMessages || hasDraft);
}

function escapeHtml(input) {
  return String(input)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function renderAssistantRichText(text) {
  let html = escapeHtml(text ?? '');
  html = html.replace(
    /\[([^\]\n]+)\]\((https?:\/\/[^\s)]+|\/api\/v1\/ui\/files\/fast\/download\?[^\s)]+)\)/g,
    (_, label, href) => {
      const isFastDownload = href.startsWith('/api/v1/ui/files/fast/download?');
      const attrs = isFastDownload ? ' download' : ' target="_blank" rel="noopener noreferrer"';
      return `<a href="${href}" class="msg-link"${attrs}>${label}</a>`;
    }
  );
  return html;
}

function extractFastAttachmentLinks(text) {
  const out = [];
  const raw = String(text ?? '');
  const re = /\[([^\]\n]+)\]\((\/api\/v1\/ui\/files\/fast\/download\?[^\s)]+)\)/g;
  let m;
  while ((m = re.exec(raw)) !== null) {
    out.push({ label: m[1], href: m[2] });
    if (out.length >= 8) break;
  }
  return out;
}

async function ask() {
  if (state.askPending) return;
  const question = $('questionInput').value.trim();
  if (!question) return;

  if (!state.currentProjectId) {
    if (state.scopeProjectId) {
      state.currentProjectId = state.scopeProjectId;
    }
  }

  if (!state.currentProjectId) {
    await ensureProjectAvailable();
    await refreshProjects();
    await refreshProjectTreeData();
  }

  if (!state.currentConversationId) {
    const created = await api(`/api/v1/ui/projects/${state.currentProjectId}/conversations`, {
      method: 'POST',
      body: JSON.stringify({ title: buildConversationTitle(question) })
    });
    state.currentConversationId = created.id;
    upsertConversationSummary(state.currentProjectId, {
      id: created.id,
      title: created.title,
      updatedAt: created.updatedAt,
      messageCount: created.messageCount || 0
    });
    state.conversationDetails[created.id] = {
      id: created.id,
      title: created.title,
      updatedAt: created.updatedAt,
      projectId: state.currentProjectId,
      messages: []
    };
  }

  const conversationId = state.currentConversationId;
  const now = Date.now();
  const detail = state.conversationDetails[conversationId] || {
    id: conversationId,
    title: buildConversationTitle(question),
    updatedAt: now,
    projectId: state.currentProjectId,
    messages: []
  };
  state.conversationDetails[conversationId] = detail;
  state.currentConversationId = conversationId;
  $('chatTitle').textContent = detail.title;

  const userMsg = {
    id: `local-user-${now}`,
    role: 'user',
    content: question,
    createdAt: now
  };
  const typingMsg = {
    id: `local-thinking-${now}`,
    role: 'assistant',
    content: '',
    typing: true,
    createdAt: now + 1
  };
  detail.messages = [...(detail.messages || []), userMsg, typingMsg];
  detail.updatedAt = now;
  upsertConversationSummary(state.currentProjectId, {
    id: conversationId,
    title: detail.title,
    updatedAt: now,
    messageCount: detail.messages.length
  });
  touchProjectUpdatedAt(state.currentProjectId, now);
  rebuildConversationIndex();
  renderProjectTree();
  renderConversationFeed();
  $('chatSubtitle').textContent = `最近更新: ${new Date(now).toLocaleString()}`;
  renderMessages(detail.messages);

  $('questionInput').value = '';
  state.askPending = true;
  setAskPending(true);
  let askResult;
  try {
    askResult = await api(`/api/v1/ui/projects/${state.currentProjectId}/conversations/${conversationId}/ask`, {
      method: 'POST',
      body: JSON.stringify({ question, mode: state.mode })
    });
  } catch (err) {
    detail.messages = detail.messages.filter((m) => m.id !== userMsg.id && m.id !== typingMsg.id);
    upsertConversationSummary(state.currentProjectId, {
      id: conversationId,
      title: detail.title,
      updatedAt: detail.updatedAt,
      messageCount: detail.messages.length
    });
    rebuildConversationIndex();
    renderProjectTree();
    renderConversationFeed();
    if (state.currentConversationId === conversationId) {
      renderMessages(detail.messages);
    }
    state.askPending = false;
    setAskPending(false);
    throw err;
  }

  const assistantMsg = askResult?.assistantMessage;
  detail.messages = detail.messages.filter((m) => m.id !== typingMsg.id);
  if (assistantMsg) {
    detail.messages = [...detail.messages, assistantMsg];
    state.expandedAssistantMessageKeys.add(assistantMessageKey(assistantMsg));
    detail.updatedAt = assistantMsg.createdAt || Date.now();
    upsertConversationSummary(state.currentProjectId, {
      id: conversationId,
      title: detail.title,
      updatedAt: detail.updatedAt,
      messageCount: detail.messages.length
    });
    touchProjectUpdatedAt(state.currentProjectId, detail.updatedAt);
    rebuildConversationIndex();
    renderProjectTree();
    renderConversationFeed();
    if (state.currentConversationId === conversationId) {
      $('chatSubtitle').textContent = `最近更新: ${new Date(detail.updatedAt).toLocaleString()}`;
      renderMessages(detail.messages);
    }
  } else {
    detail.updatedAt = Date.now();
    if (state.currentConversationId === conversationId) {
      renderMessages(detail.messages);
    }
  }
  state.askPending = false;
  setAskPending(false);
}

async function downloadExport(url, filename) {
  const blob = await api(url, { headers: { 'X-Auth-Token': state.token } });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}

function initResizer() {
  const handle = $('railResize');
  if (!handle) return;
  let dragging = false;

  handle.addEventListener('mousedown', (e) => {
    dragging = true;
    e.preventDefault();
  });
  window.addEventListener('mousemove', (e) => {
    if (!dragging || state.railCollapsed) return;
    state.railWidth = Math.min(420, Math.max(220, e.clientX));
    localStorage.setItem('ui_rail_width', String(state.railWidth));
    applyRailState();
  });
  window.addEventListener('mouseup', () => {
    dragging = false;
  });
}

function bindActions() {
  $('themeToggle').onclick = () => toggleMode();
  $('logoutBtn').onclick = logout;
  $('loginBtn').onclick = () => login().catch((e) => toast(e.message));
  $('registerBtn').onclick = () => register().catch((e) => toast(e.message));
  $('hintBtn').onclick = () => fetchHint().catch((e) => toast(e.message));
  bindPasswordToggle('toggleLoginPassword', 'loginPassword');
  bindPasswordToggle('toggleRegisterPassword', 'registerPassword');

  $('createProjectBtn').onclick = () => createProject().catch((e) => toast(e.message));
  $('newChatBtn').onclick = () => newChat().catch((e) => toast(e.message));
  $('askBtn').onclick = () => ask().catch((e) => toast(e.message));

  const onProjectSearch = debounce((e) => {
    state.projectQuery = e.target.value || '';
    renderProjectTree();
    renderConversationFeed();
  }, 100);
  $('projectSearch').addEventListener('input', onProjectSearch);
  $('clearProjectScopeBtn').onclick = () => {
    cancelPendingConversationLoad();
    state.scopeProjectId = '';
    renderProjectTree();
    renderConversationFeed();
  };

  $('questionInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      ask().catch((err) => toast(err.message));
    }
  });
  $('questionInput').addEventListener('input', () => updateSupportHint());

  const toggleRailBtn = $('toggleRailBtn');
  if (toggleRailBtn) {
    toggleRailBtn.onclick = () => {
      state.railCollapsed = !state.railCollapsed;
      localStorage.setItem('ui_rail_collapsed', state.railCollapsed ? '1' : '0');
      applyRailState();
      $('railResize').classList.toggle('hidden', state.railCollapsed || !state.token);
    };
  }

  $('userMenuBtn').onclick = () => toggleDrawer(true);
  $('closeDrawerBtn').onclick = () => toggleDrawer(false);
  $('drawerThemeBtn').onclick = () => toggleMode();
  $('drawerLogoutBtn').onclick = logout;
  $('inviteImportBtn').onclick = () => importInvites().catch((e) => toast(e.message));
  $('inviteRandomBtn').onclick = () => createRandomInvites().catch((e) => toast(e.message));

  $('exportConversationPdf').onclick = () => {
    if (!state.currentProjectId || !state.currentConversationId) return toast('请先打开一个对话');
    downloadExport(`/api/v1/ui/export/projects/${state.currentProjectId}/conversations/${state.currentConversationId}?format=pdf`, 'conversation.pdf').catch((e) => toast(e.message));
  };
  $('exportConversationDocx').onclick = () => {
    if (!state.currentProjectId || !state.currentConversationId) return toast('请先打开一个对话');
    downloadExport(`/api/v1/ui/export/projects/${state.currentProjectId}/conversations/${state.currentConversationId}?format=docx`, 'conversation.docx').catch((e) => toast(e.message));
  };
  $('exportProjectPdf').onclick = () => {
    const projectId = state.scopeProjectId || state.currentProjectId;
    if (!projectId) return toast('请先选择项目');
    downloadExport(`/api/v1/ui/export/projects/${projectId}?format=pdf`, 'project.pdf').catch((e) => toast(e.message));
  };
  $('exportProjectDocx').onclick = () => {
    const projectId = state.scopeProjectId || state.currentProjectId;
    if (!projectId) return toast('请先选择项目');
    downloadExport(`/api/v1/ui/export/projects/${projectId}?format=docx`, 'project.docx').catch((e) => toast(e.message));
  };

  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') toggleDrawer(false);
  });
}

async function refreshAll() {
  await refreshProjects();
  await ensureProjectAvailable();
  await refreshProjects();
  await refreshProjectTreeData();
  renderProjectTree();
  renderConversationFeed();
  const current = state.projects.find((p) => p.id === state.currentProjectId);
  setChatIdle(current?.name || '');
  updateSupportHint([]);
}

async function init() {
  setTheme(localStorage.getItem('ui_theme') || 'light');
  applyModeUI();
  applyRailState();
  await loadAuthConfig();
  bindActions();
  initResizer();

  if (!state.token) {
    switchView(false);
    return;
  }

  try {
    await loadMe();
    switchView(true);
    await refreshAll();
  } catch (_) {
    logout();
  }
}

init();
