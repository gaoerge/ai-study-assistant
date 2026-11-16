'use strict';

const form = document.querySelector('#question-form');
const question = document.querySelector('#question');
const submit = document.querySelector('#submit');
const formError = document.querySelector('#form-error');
const statusLabel = document.querySelector('#status');
const message = document.querySelector('#answer-message');
const answer = document.querySelector('#answer');
const askedQuestion = document.querySelector('#answered-question');
const notice = document.querySelector('#citation-notice');
const citedSources = document.querySelector('#cited-sources');
const candidateSources = document.querySelector('#candidate-sources');
const otherSources = document.querySelector('#other-sources');
const answerSection = document.querySelector('#answer-section');
let loading = false;

// 左侧只切换当前工作视图，输入和本次结果保留；不创建多轮会话。
function switchView(view) {
  const sourcesVisible = view === 'sources';
  document.querySelector('#ask-view').hidden = sourcesVisible;
  document.querySelector('#sources').hidden = !sourcesVisible;
  document.querySelector('#view-title').textContent = sourcesVisible ? '核对引用' : '资料提问';
  document.querySelectorAll('.page-nav [data-view]').forEach(button => {
    if (button.dataset.view === view) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
}

document.querySelector('.skip-link').addEventListener('click', event => {
  event.preventDefault();
  switchView('ask');
  question.focus();
});

document.querySelectorAll('[data-view]').forEach(button => {
  button.addEventListener('click', () => {
    switchView(button.dataset.view);
    if (button.dataset.view === 'ask') question.focus();
    else document.querySelector('#sources-title').focus();
  });
});

const states = {
  answered: ['已返回回答', '请结合引用原文阅读回答。'],
  insufficient_evidence: ['资料不足', '当前资料不足以回答这个问题，可先查看候选片段。'],
  invalid_model_response: ['模型响应无效', '模型响应或引用编号校验失败，可先查看候选片段。'],
  model_error: ['模型调用失败', '模型调用失败，请稍后重试，也可以先查看候选片段。']
};

function element(tag, text, className) {
  const node = document.createElement(tag);
  node.textContent = text;
  if (className) node.className = className;
  return node;
}

function emptySources(container, text) {
  container.replaceChildren(element('p', text, 'empty'));
}

function setStatus(label, detail, kind) {
  statusLabel.textContent = label;
  statusLabel.dataset.kind = kind;
  message.textContent = detail;
}

function clearResult() {
  answer.replaceChildren();
  answer.hidden = true;
  askedQuestion.hidden = true;
  notice.hidden = true;
  citedSources.replaceChildren();
  candidateSources.replaceChildren();
  document.querySelector('#cited-count').textContent = '0';
  document.querySelector('#other-count').textContent = '0';
  otherSources.open = false;
}

// 校验响应结构，避免把缺字段或不一致的响应呈现为成功。
function validResponse(data) {
  if (!data || typeof data.status !== 'string' || typeof data.message !== 'string'
      || typeof data.answer !== 'string' || !Array.isArray(data.citations)
      || !Array.isArray(data.citedNumbers)) return false;
  const numbers = new Set();
  for (const citation of data.citations) {
    const chunk = citation?.chunk;
    if (!Number.isSafeInteger(citation?.number) || citation.number < 1 || numbers.has(citation.number)
        || !chunk || !['fileName', 'heading', 'content'].every(key => typeof chunk[key] === 'string')
        || !Number.isSafeInteger(chunk.startLine) || !Number.isSafeInteger(chunk.endLine)
        || chunk.startLine < 1 || chunk.endLine < chunk.startLine) return false;
    numbers.add(citation.number);
  }
  if (!data.citedNumbers.every(number => Number.isSafeInteger(number) && numbers.has(number))) return false;
  if (data.status === 'answered') {
    const textNumbers = [...data.answer.matchAll(/\[([0-9]+)\]/g)].map(match => Number(match[1]));
    const cited = new Set(data.citedNumbers);
    return data.answer.trim().length > 0 && cited.size > 0
      && textNumbers.every(number => cited.has(number))
      && [...cited].every(number => textNumbers.includes(number));
  }
  return true;
}

function sourceCard(citation) {
  const { number, chunk } = citation;
  const card = element('details', '', 'source-card');
  card.id = `source-${number}`;
  const summary = document.createElement('summary');
  summary.append(element('span', `[${number}]`, 'source-number'), document.createTextNode(chunk.heading || '未命名标题'));
  summary.append(element('span', `${chunk.fileName} · 第 ${chunk.startLine}–${chunk.endLine} 行`, 'source-meta'));
  const original = element('pre', chunk.content);
  original.tabIndex = 0;
  original.setAttribute('aria-label', `引用 ${number} 原文片段，可横向滚动`);
  card.append(summary, original);
  return card;
}

function showSources(citations, citedNumbers) {
  const cited = new Set(citedNumbers);
  const used = citations.filter(citation => cited.has(citation.number));
  const others = citations.filter(citation => !cited.has(citation.number));
  document.querySelector('#cited-count').textContent = String(used.length);
  document.querySelector('#other-count').textContent = String(others.length);
  citedSources.replaceChildren(...used.map(sourceCard));
  candidateSources.replaceChildren(...others.map(sourceCard));
  if (!used.length) emptySources(citedSources, '本次没有实际引用的资料。');
  if (!others.length) emptySources(candidateSources, '没有其他候选资料。');
}

function showAnswer(text, citedNumbers) {
  const cited = new Set(citedNumbers);
  let offset = 0;
  for (const match of text.matchAll(/\[([0-9]+)\]/g)) {
    answer.append(document.createTextNode(text.slice(offset, match.index)));
    const number = Number(match[1]);
    if (cited.has(number)) {
      const button = element('button', match[0], 'citation-link');
      button.type = 'button';
      button.setAttribute('aria-label', `查看引用 ${number} 原文`);
      button.addEventListener('click', () => {
        switchView('sources');
        const card = document.getElementById(`source-${number}`);
        card.open = true;
        card.querySelector('summary').focus({ preventScroll: true });
        card.scrollIntoView({ block: 'nearest' });
      });
      answer.append(button);
    } else {
      answer.append(document.createTextNode(match[0]));
    }
    offset = match.index + match[0].length;
  }
  answer.append(document.createTextNode(text.slice(offset)));
  answer.hidden = false;
}

function fail(label, detail) {
  setStatus(label, detail, 'error');
  formError.textContent = detail;
  formError.hidden = false;
  emptySources(citedSources, '本次没有可展示的引用。');
  emptySources(candidateSources, '本次没有可展示的候选资料。');
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (loading) return;
  const text = question.value.trim();
  formError.hidden = true;
  question.removeAttribute('aria-invalid');
  if (!text) {
    formError.textContent = '请先输入一个问题。';
    formError.hidden = false;
    question.setAttribute('aria-invalid', 'true');
    question.focus();
    return;
  }
  switchView('ask');
  document.querySelector('#welcome').hidden = true;
  answerSection.hidden = false;
  loading = true;
  submit.disabled = true;
  submit.textContent = '正在提问…';
  answerSection.setAttribute('aria-busy', 'true');
  clearResult();
  setStatus('正在处理', '正在检索资料并等待回答…', 'loading');
  emptySources(citedSources, '等待本次回答的引用。');
  emptySources(candidateSources, '等待本次候选资料。');
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 90000);
  try {
    const response = await fetch('/questions/answer', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify({ question: text }),
      signal: controller.signal
    });
    if (!response.ok) {
      let detail = '服务返回错误，请稍后重试。';
      try {
        const error = await response.json();
        if (typeof error?.message === 'string') detail = error.message;
      } catch { /* HTTP 状态仍可显示，错误页可能不是 JSON。 */ }
      fail('请求失败', `HTTP ${response.status} · ${detail}`);
      return;
    }
    let data;
    try {
      data = await response.json();
    } catch {
      if (controller.signal.aborted) throw new Error('request_timeout');
      fail('响应无法解析', '服务返回了无法解析的响应，请重试。');
      return;
    }
    if (!validResponse(data)) {
      fail('响应格式异常', '响应字段缺失或引用数据不一致，请重试。');
      return;
    }
    const knownStatus = Object.hasOwn(states, data.status);
    // 未知状态不采信其实际引用声明，但仍允许核对返回的候选。
    showSources(data.citations, knownStatus ? data.citedNumbers : []);
    askedQuestion.textContent = text;
    askedQuestion.hidden = false;
    if (!knownStatus) {
      setStatus('未知状态', '服务返回了未知状态，暂时无法展示回答，可查看候选资料。', 'error');
      return;
    }
    const [label, detail] = states[data.status];
    setStatus(label, detail, data.status === 'answered' ? 'answered' : 'error');
    if (data.status === 'answered') {
      showAnswer(data.answer, data.citedNumbers);
      notice.hidden = false;
    } else {
      formError.textContent = data.message || detail;
      formError.hidden = false;
    }
  } catch {
    fail(controller.signal.aborted ? '请求超时' : '网络连接失败',
      controller.signal.aborted ? '等待回答超时，请重试。' : '无法连接服务，请检查网络或服务是否运行后重试。');
  } finally {
    clearTimeout(timeout);
    loading = false;
    submit.disabled = false;
    submit.textContent = '提问 ↑';
    answerSection.setAttribute('aria-busy', 'false');
  }
});

question.addEventListener('keydown', event => {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) {
    event.preventDefault();
    form.requestSubmit();
  }
});

document.querySelectorAll('.examples button').forEach(button => {
  button.addEventListener('click', () => {
    question.value = button.textContent;
    question.removeAttribute('aria-invalid');
    formError.hidden = true;
    question.focus();
  });
});
