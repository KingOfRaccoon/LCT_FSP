import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router';
import { api, errorMessage, RequestError, type Schemas, type User } from '../../api';
import { clearConversationAfterAccessDenied, createPendingMessage, isConversationResponseCurrent } from './task-helpers';
import './extensions.css';

type Message = Schemas['InvitationMessage'];
type MessagePage = Schemas['InvitationMessagePage'];
type PendingMessage = { body: string; idempotencyKey: string };

export function ConversationPage({ role: roleProp }: { role?: 'candidate' | 'employer' } = {}) {
  const { invitationId = '' } = useParams();
  const [role, setRole] = useState(roleProp);
  const [page, setPage] = useState<MessagePage>();
  const [messages, setMessages] = useState<Message[]>([]);
  const [draft, setDraft] = useState('');
  const [pending, setPending] = useState<PendingMessage>();
  const [loading, setLoading] = useState(true);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [saving, setSaving] = useState(false);
  const [closed, setClosed] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const requestGeneration = useRef(0);
  const accessGeneration = useRef(0);
  const olderRequestGeneration = useRef(0);
  const sendRequestGeneration = useRef(0);
  const messagesRef = useRef(messages);
  messagesRef.current = messages;

  useEffect(() => {
    if (roleProp) { setRole(roleProp); return; }
    let active = true;
    api.get<User>('/auth/me').then((user) => { if (active) setRole(user.account_type); }).catch(() => { if (active) setRole(undefined); });
    return () => { active = false; };
  }, [roleProp]);

  const refresh = useCallback(async (showLoading = false) => {
    const generation = ++requestGeneration.current;
    const accessAtStart = accessGeneration.current;
    const isCurrent = () => isConversationResponseCurrent({ requestGeneration: generation, currentRequestGeneration: requestGeneration.current, accessGeneration: accessAtStart, currentAccessGeneration: accessGeneration.current });
    if (!invitationId) { setClosed(true); setMessages([]); setLoading(false); return; }
    if (showLoading) setLoading(true);
    try {
      const result = await api.get<MessagePage>(`/invitations/${encodeURIComponent(invitationId)}/messages?limit=50`);
      if (!isCurrent()) return;
      setPage(result);
      setClosed(false);
      setMessages((current) => {
        const byId = new Map(current.map((message) => [message.id, message]));
        result.items.forEach((message) => byId.set(message.id, message));
        return [...byId.values()].sort((a, b) => Date.parse(a.created_at) - Date.parse(b.created_at));
      });
      setError('');
    } catch (reason) {
      if (!isCurrent()) return;
      const status = reason instanceof RequestError ? reason.status : 0;
      const access = clearConversationAfterAccessDenied({ status, messages: messagesRef.current });
      if (access.closed) { accessGeneration.current += 1; setMessages(access.messages); setPage(undefined); setClosed(true); setError('Доступ к переписке закрыт.'); setLoading(false); }
      else setError(errorMessage(reason));
    } finally { if (isCurrent()) setLoading(false); }
  }, [invitationId]);

  useEffect(() => {
    accessGeneration.current += 1;
    setPage(undefined); setMessages([]); setClosed(false); setDraft(''); setPending(undefined);
    setSaving(false); setLoadingOlder(false);
    void refresh(true);
    const onFocus = () => { if (document.visibilityState === 'visible') void refresh(); };
    const interval = window.setInterval(() => { if (document.visibilityState === 'visible') void refresh(); }, 30_000);
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onFocus);
    return () => { requestGeneration.current += 1; accessGeneration.current += 1; window.clearInterval(interval); window.removeEventListener('focus', onFocus); document.removeEventListener('visibilitychange', onFocus); };
  }, [refresh]);

  const loadOlder = async () => {
    if (!page?.next_cursor || loadingOlder) return;
    setLoadingOlder(true); setError('');
    const request = ++olderRequestGeneration.current;
    const accessAtStart = accessGeneration.current;
    const isCurrent = () => isConversationResponseCurrent({ requestGeneration: request, currentRequestGeneration: olderRequestGeneration.current, accessGeneration: accessAtStart, currentAccessGeneration: accessGeneration.current });
    try {
      const older = await api.get<MessagePage>(`/invitations/${encodeURIComponent(invitationId)}/messages?limit=50&cursor=${encodeURIComponent(page.next_cursor)}`);
      if (!isCurrent() || closed) return;
      setPage((current) => current ? { ...current, next_cursor: older.next_cursor } : older);
      setMessages((current) => {
        const seen = new Set(current.map((message) => message.id));
        return [...older.items.filter((message) => !seen.has(message.id)), ...current]
          .sort((a, b) => Date.parse(a.created_at) - Date.parse(b.created_at));
      });
    } catch (reason) {
      if (!isCurrent()) return;
      const status = reason instanceof RequestError ? reason.status : 0;
      if (status === 401 || status === 403 || status === 404) { accessGeneration.current += 1; setMessages([]); setPage(undefined); setClosed(true); setError('Доступ к переписке закрыт.'); setLoadingOlder(false); }
      else setError(errorMessage(reason));
    } finally { if (isCurrent()) setLoadingOlder(false); }
  };

  const sendMessage = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const body = (pending?.body ?? draft).trim();
    if (!body || body.length > 2000 || !page?.can_send || saving) return;
    const request = createPendingMessage(body, () => crypto.randomUUID(), pending);
    setPending(request); setSaving(true); setError(''); setNotice('');
    const sendGeneration = ++sendRequestGeneration.current;
    const accessAtStart = accessGeneration.current;
    const isCurrent = () => isConversationResponseCurrent({ requestGeneration: sendGeneration, currentRequestGeneration: sendRequestGeneration.current, accessGeneration: accessAtStart, currentAccessGeneration: accessGeneration.current });
    try {
      const saved = await api.post<Message>(`/invitations/${encodeURIComponent(invitationId)}/messages`, { text: request.body }, request.idempotencyKey);
      if (!isCurrent() || closed) return;
      setMessages((current) => current.some((message) => message.id === saved.id) ? current : [...current, saved].sort((a, b) => Date.parse(a.created_at) - Date.parse(b.created_at)));
      setDraft(''); setPending(undefined); setNotice('Сообщение сохранено.');
      void refresh();
    } catch (reason) {
      if (!isCurrent()) return;
      const status = reason instanceof RequestError ? reason.status : 0;
      if (status === 401 || status === 403 || status === 404) { accessGeneration.current += 1; setMessages([]); setPage(undefined); setClosed(true); setError('Доступ к переписке закрыт.'); setSaving(false); }
      else setError(errorMessage(reason));
    } finally { if (isCurrent()) setSaving(false); }
  };

  const salary = page?.salary;
  const salaryText = salary ? `${(salary.min_kopecks / 100).toLocaleString('ru-RU')}–${(salary.max_kopecks / 100).toLocaleString('ru-RU')} ${salary.currency === 'RUB' ? '₽' : salary.currency} / ${salary.period === 'month' ? 'месяц' : salary.period}` : '';
  const canSend = Boolean(page?.can_send && role && !closed);

  return <section className="extension-page conversation-page" aria-labelledby="conversation-title">
    <div className="extension-header"><div className="eyebrow">ПЕРЕПИСКА ПО ПРИГЛАШЕНИЮ</div><h1 id="conversation-title">{page?.company_name || (closed ? 'Переписка недоступна' : 'Загрузка переписки')}</h1><p>Сообщения обсуждают предложение и не меняют его условия или статус приглашения.</p></div>
    {page && <section className="extension-context" aria-label="Условия приглашения"><strong>Условия приглашения</strong><p>{page.description}</p><dl><div><dt>Зарплатная вилка</dt><dd>{salaryText}</dd></div></dl></section>}
    {error && <div className="extension-alert" role="alert">{error}<button type="button" className="text-button" onClick={() => void refresh(true)}>Обновить</button></div>}
    {notice && <p className="extension-status" role="status">{notice}</p>}
    {loading && <p className="field-hint" role="status">Загружаем сообщения…</p>}
    {!loading && !closed && <div className="conversation-shell">
      {page?.next_cursor && <button className="button button-secondary extension-older" type="button" disabled={loadingOlder} onClick={() => void loadOlder()}>{loadingOlder ? 'Загружаем…' : 'Загрузить более ранние сообщения'}</button>}
      <ol className="conversation-messages" aria-live="polite" aria-relevant="additions text">{messages.map((message) => {
        const own = message.sender_role === role;
        return <li className={`conversation-message ${own ? 'is-own' : ''}`} key={message.id}><div className="conversation-message-meta"><strong>{message.sender_role === 'candidate' ? 'Кандидат' : page?.company_name || 'Компания'}{own ? ' · вы' : ''}</strong><time dateTime={message.created_at}>{new Date(message.created_at).toLocaleString('ru-RU')}</time></div><p>{message.text}</p></li>;
      })}{!messages.length && <li className="conversation-empty">Сообщений пока нет. Можно уточнить детали предложения.</li>}</ol>
    </div>}
    {closed && !loading && <p className="extension-closed" role="status">История не отображается, потому что сервер закрыл доступ к этой переписке.</p>}
    {page && !page.can_send && !closed && <p className="extension-closed" role="status">Переписка доступна для чтения. Отправка сообщений закрыта.</p>}
    {canSend && <form className="conversation-compose" onSubmit={(event) => void sendMessage(event)}>
      <label className="field"><span>Ваше сообщение</span><textarea value={pending?.body ?? draft} onChange={(event) => { const value = event.target.value; setDraft(value); if (pending && value !== pending.body) setPending(undefined); }} maxLength={2000} rows={4} aria-describedby="conversation-message-hint" /></label>
      <div className="conversation-compose-foot"><small id="conversation-message-hint">До 2&nbsp;000 символов. Предложение не меняется через переписку.</small><span>{(pending?.body ?? draft).length}/2000</span><button className="button button-primary" type="submit" disabled={saving || !(pending?.body ?? draft).trim()}>{saving ? 'Сохраняем…' : error && pending ? 'Повторить отправку' : 'Отправить'}</button></div>
    </form>}
  </section>;
}
