import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api, errorMessage, type Schemas } from '../../api';
import './candidate.css';
import './QualificationPortfolio.css';

const dateLabel = (value: string) => new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium' }).format(new Date(value));
const gradeLabels: Record<string, string> = { junior: 'Junior', middle: 'Middle' };

export function QualificationPortfolio() {
  const portfolio = useQuery({
    queryKey: ['me', 'qualifications'],
    queryFn: () => api.get<Schemas['QualificationPortfolio']>('/me/qualifications'),
  });

  if (portfolio.isPending) {
    return <section className="candidate-feature-stack" aria-busy="true"><p role="status">Загружаем подтверждения…</p></section>;
  }

  if (portfolio.isError) {
    return <section className="candidate-feature-stack">
      <header className="candidate-feature-heading">
        <span className="candidate-eyebrow">ПРОФЕССИОНАЛЬНЫЕ ПОДТВЕРЖДЕНИЯ</span>
        <h1>Моя квалификация</h1>
      </header>
      <div className="candidate-inline-error" role="alert">{errorMessage(portfolio.error)}</div>
      <button className="button" type="button" onClick={() => void portfolio.refetch()} disabled={portfolio.isFetching}>Повторить</button>
    </section>;
  }

  const { items, available_specializations: available, catalog_note: catalogNote } = portfolio.data;

  return <section className="candidate-feature-stack">
    <header className="candidate-feature-heading">
      <span className="candidate-eyebrow">ПРОФЕССИОНАЛЬНЫЕ ПОДТВЕРЖДЕНИЯ</span>
      <h1>Моя квалификация</h1>
      <p>Здесь показаны только записи оценки платформы. Опыт, навыки и уровень в профиле остаются заявлениями кандидата.</p>
    </header>

    {items.length === 0 ? <section className="card candidate-empty-state" aria-label="Подтверждения квалификации отсутствуют">
      <strong>Пока нет подтверждённых результатов</strong>
      <p>Пройдите доступную оценку, чтобы увидеть её запись и методику. Заявленные в профиле навыки от этого не меняются.</p>
      <Link className="inline-link" to="/assessment">Открыть раздел оценки →</Link>
    </section> : items.map((item) => {
      const grade = item.grade;
      const published = item.method_status === 'published_demo' && grade !== null;
      return <article className="card candidate-qualification-card" key={item.specialization_id}>
        <div className="candidate-section-heading">
          <div>
            <span className="candidate-eyebrow">{item.specialization_id}</span>
            <h2>{item.label}</h2>
          </div>
          <span className={published ? 'candidate-demo-marker' : 'candidate-visibility-chip'}>
            {published ? 'Демонстрационная методика' : 'Методика недоступна'}
          </span>
        </div>
        {published ? <dl className="candidate-qualification-facts">
          <div><dt>Результат оценки</dt><dd>{grade === null ? '—' : gradeLabels[grade] ?? grade}</dd></div>
          <div><dt>Источник</dt><dd>Оценка платформы</dd></div>
          <div><dt>Дата</dt><dd><time dateTime={item.assessed_at}>{dateLabel(item.assessed_at)}</time></dd></div>
          <div><dt>Методика</dt><dd>Policy {item.policy_version} · банк {item.bank_version}</dd></div>
        </dl> : <p className="candidate-method-footnote">Запись специализации сохранена, но её методика или доказательство сейчас недоступны. Уровень не показывается.</p>}
        <p className="candidate-method-footnote">Демонстрационный результат описывает прохождение опубликованной методики и не подтверждает профессиональную пригодность.</p>
      </article>;
    })}

    <section className="candidate-inline-info" aria-label="Доступные специализации">
      <strong>Доступный каталог: {available.map((specialization) => specialization.label).join(', ') || 'пока пуст'}</strong>
      <span>{catalogNote}</span>
    </section>
  </section>;
}
