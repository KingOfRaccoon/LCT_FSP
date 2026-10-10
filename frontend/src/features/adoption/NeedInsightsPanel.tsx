import { useEffect, useRef, useState } from 'react';
import { api, errorMessage, skillLabels, type Need } from '../../api';
import type { NeedInsights } from './types';
import './adoption.css';

const countLabels: [keyof NeedInsights['counts'], string][] = [
  ['confirmed', 'Подходят по подтверждённым сведениям'],
  ['needs_clarification', 'Нужно уточнить сведения'],
  ['needs_confirmation', 'Нужно подтверждение'],
  ['excluded', 'Исключены условиями'],
  ['demo', 'Демонстрационные профили'],
];
const currency = (kopecks: number) => `${Math.round(kopecks / 100).toLocaleString('ru-RU')} ₽`;
const privacyCount = (value: number | null) => value === null ? 'Неизвестно' : value.toLocaleString('ru-RU');

export function NeedInsightsPanel({ need }: { need: Need }) {
  const [data, setData] = useState<NeedInsights>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [preview, setPreview] = useState<string>();
  const requestId = useRef(0);
  const version = need.version;
  const path = `/needs/${encodeURIComponent(need.id)}/insights?expected_version=${encodeURIComponent(String(version))}`;

  useEffect(() => {
    const request = ++requestId.current;
    setData(undefined); setLoading(true); setError(''); setPreview(undefined);
    void api.get<NeedInsights>(path).then((result) => {
      if (request !== requestId.current) return;
      if (result.need_id !== need.id || result.need_version !== version) {
        setError('Получена аналитика для другой версии потребности. Обновите страницу.');
        return;
      }
      setData(result);
    }).catch((cause: unknown) => {
      if (request === requestId.current) setError(errorMessage(cause));
    }).finally(() => { if (request === requestId.current) setLoading(false); });
    return () => { requestId.current += 1; };
  }, [need.id, version, path]);

  const current = data?.need_id === need.id && data.need_version === version ? data : undefined;
  return <section className="card adoption-card adoption-insights" aria-labelledby={`need-insights-title-${need.id}`}>
    <div className="card-head"><div><span className="eyebrow">АНАЛИТИКА ПОТРЕБНОСТИ</span><h2 id={`need-insights-title-${need.id}`}>Кого ограничивают условия</h2></div>{current && <span className="badge badge-neutral">Версия {current.need_version}</span>}</div>
    <p className="adoption-intro">Срез текущих видимых профилей платформы для этой потребности. Это не оценка всего рынка и не прогноз найма.</p>
    {loading && <p role="status" className="loading">Загружаем срез по текущей версии…</p>}
    {error && <div className="error-box" role="alert"><span>{error}</span><button className="text-button" type="button" disabled={loading} onClick={() => { setError(''); setLoading(true); const request = ++requestId.current; void api.get<NeedInsights>(path).then((result) => { if (request === requestId.current && result.need_id === need.id && result.need_version === version) setData(result); else if (request === requestId.current) setError('Получена аналитика для другой версии потребности. Обновите страницу.'); }).catch((cause: unknown) => { if (request === requestId.current) setError(errorMessage(cause)); }).finally(() => { if (request === requestId.current) setLoading(false); }); }}>Повторить</button></div>}
    {current && <>
      <div className="adoption-pool-summary"><strong>{current.counts.profile_pool.toLocaleString('ru-RU')}</strong><span>профилей в текущей видимой выборке</span></div>
      <dl className="adoption-counts">{countLabels.map(([key, label]) => <div key={key}><dt>{label}</dt><dd>{current.counts[key].toLocaleString('ru-RU')}</dd></div>)}</dl>
      <div className="adoption-unknown-note"><strong>Нет подтверждения тестом — это неизвестность</strong><span>{current.mandatory_requirements.unknown_test_evidence_pairs} пар «профиль — обязательный навык» не имеют тестового подтверждения. {current.mandatory_requirements.blocked_by_matching_policy_count} профилей исключены по правилам подбора; отсутствие сигнала о навыке само по себе не доказывает его отсутствие.</span></div>
      <h3>Влияние требований к навыкам</h3>
      {current.requirements.length === 0 ? <p className="field-hint">Навыковые требования не заданы.</p> : <div className="adoption-requirements">{current.requirements.map((requirement) => {
        const label = skillLabels[requirement.skill_id] ?? requirement.skill_id;
        const open = preview === requirement.skill_id;
        return <article key={`${requirement.skill_id}-${requirement.importance}`} className="adoption-requirement">
          <div className="adoption-requirement-heading"><strong>{label}</strong><span className={requirement.importance === 'required' ? 'badge badge-warm' : 'badge badge-neutral'}>{requirement.importance === 'required' ? 'Обязательный' : 'Желательный'}</span></div>
          <dl className="adoption-requirement-counts"><div><dt>Подтверждён тестом</dt><dd>{requirement.test_confirmed_count}</dd></div><div><dt>Только заявлен</dt><dd>{requirement.declared_only_count}</dd></div><div><dt>Нет сигнала в профиле</dt><dd>{requirement.no_skill_signal_count}</dd></div></dl>
          {requirement.importance === 'required' && <><button className="text-button" type="button" aria-expanded={open} onClick={() => setPreview(open ? undefined : requirement.skill_id)}>Показать эффект, если убрать требование</button>{open && <p className="adoption-preview" role="status">Ещё {requirement.additional_confirmed_matches_if_relaxed.toLocaleString('ru-RU')} профилей будут соответствовать обязательным условиям. Это только предварительный расчёт; условия изменит компания вручную.</p>}</>}
        </article>;
      })}</div>}
      <div className="adoption-salary">
        <h3>Зарплатные ожидания в фиксированной когорте</h3>
        <p className="field-hint">Срез видимых профилей той же специализации и грейда. Он не меняется при смене требований, города или режима работы в этой потребности, поэтому эти цифры не описывают рынок или только кандидатов под текущую вакансию.</p>
        <p className="field-hint">Счётчики и медиана отображаются, только если в когорте и среди совместимых ожиданий есть не менее пяти наблюдений. «Неизвестно» не означает ноль; медианные суммы округлены до 10&nbsp;000 ₽.</p>
        <dl className="adoption-salary-counts"><div><dt>Есть зарплатные ожидания</dt><dd>{privacyCount(current.salary.known_count)}</dd></div><div><dt>Совместимы с форматом занятости</dt><dd>{privacyCount(current.salary.compatible_count)}</dd></div><div><dt>Ожидания неизвестны</dt><dd>{privacyCount(current.salary.unknown_count)}</dd></div><div><dt>Несовместимы с форматом</dt><dd>{privacyCount(current.salary.incompatible_count)}</dd></div></dl>
        {current.salary.summary_available && current.salary.median_min_kopecks !== null && current.salary.median_max_kopecks !== null ? <p className="adoption-median">Округлённая медианная вилка когорты: <strong>{currency(current.salary.median_min_kopecks)}–{currency(current.salary.median_max_kopecks)}</strong></p> : <p className="notice">Недостаточно наблюдений для безопасного показа зарплатного агрегата; суммы скрыты.</p>}
        <p className="notice">Система не показывает число профилей, чьи ожидания попадают в зарплатную вилку именно этой потребности. Такой счётчик скрыт для защиты приватности.</p>
      </div>
      <p className="adoption-data-note">Расчёт создан {new Date(current.generated_at).toLocaleString('ru-RU')} · версия политики {current.policy_version}. Профили вне выборки не учитываются.</p>
    </>}
    {!loading && !error && !current && <p role="status" className="field-hint">Для этой версии потребности аналитика пока недоступна.</p>}
  </section>;
}
