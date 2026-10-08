import { Link } from 'react-router';

type BrandProps = {
  to?: string;
  variant?: 'compact' | 'full';
  tone?: 'color' | 'light';
};

export default function Brand({ to = '/', variant = 'compact', tone = 'color' }: BrandProps) {
  const asset = `/brand/fsp-logo-${variant}${tone === 'light' ? '-white' : ''}.svg`;
  return <Link to={to} className={`brand brand-${variant}${tone === 'light' ? ' brand-light' : ''}`} aria-label="ФСП · Таланты — главная">
    <img className="brand-logo" src={asset} alt="Федерация спортивного программирования России" width={variant === 'full' ? 400 : 150} height={variant === 'full' ? 50 : 50} />
    <span className="brand-product">Таланты</span>
  </Link>;
}
