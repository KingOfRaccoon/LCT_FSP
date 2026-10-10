import { useEffect, useState } from 'react';
import { api, errorMessage, type Schemas } from '../../api';

export type Need = Schemas['Need'];

export interface NeedSelectProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  allOptionLabel?: string;
  includeAllOption?: boolean;
  activeOnly?: boolean;
  disabled?: boolean;
  required?: boolean;
  className?: string;
  id?: string;
  name?: string;
}

const gradeLabel = (grade: string): string => {
  if (grade === 'junior') return 'Junior';
  if (grade === 'middle') return 'Middle';
  return grade;
};

export function NeedSelect({
  value,
  onChange,
  placeholder,
  allOptionLabel,
  includeAllOption = true,
  activeOnly = true,
  disabled = false,
  required = false,
  className = '',
  id,
  name,
}: NeedSelectProps) {
  const [needs, setNeeds] = useState<Need[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');

    api.get<{ items: Need[] }>('/needs?limit=100')
      .then((data) => {
        if (!active) return;
        setNeeds(data.items || []);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(errorMessage(err));
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, []);

  const visibleNeeds = needs.filter((need) => {
    if (!activeOnly) return true;
    return need.status === 'active' || need.id === value;
  });

  return (
    <select
      id={id}
      name={name}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      disabled={disabled || (loading && needs.length === 0)}
      required={required}
      className={`recruiting-select recruiting-need-select ${className}`.trim()}
      aria-label="Выбор потребности"
    >
      {includeAllOption && (
        <option value="">{allOptionLabel || placeholder || 'Все потребности'}</option>
      )}
      {!includeAllOption && !value && (
        <option value="" disabled>
          {placeholder || 'Выберите потребность'}
        </option>
      )}
      {loading && needs.length === 0 && (
        <option value="" disabled>Загрузка потребностей…</option>
      )}
      {error && needs.length === 0 && (
        <option value="" disabled>Ошибка загрузки потребностей</option>
      )}
      {visibleNeeds.map((need) => (
        <option key={need.id} value={need.id}>
          {need.title} ({gradeLabel(need.target_grade)}){need.status !== 'active' ? ` [${need.status}]` : ''}
        </option>
      ))}
    </select>
  );
}
