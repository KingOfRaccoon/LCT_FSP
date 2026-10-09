import type { ReactNode } from 'react';

export type NavIconName =
  | 'overview' | 'profile' | 'visibility' | 'assessment' | 'credentials' | 'tasks'
  | 'invitations' | 'contacts' | 'company' | 'needs' | 'matches' | 'bank'
  | 'shortlists' | 'recruiting' | 'menu' | 'logout';

const paths: Record<NavIconName, ReactNode> = {
  overview: <><rect x="4" y="4" width="6" height="6" rx="1" /><rect x="14" y="4" width="6" height="6" rx="1" /><rect x="4" y="14" width="6" height="6" rx="1" /><rect x="14" y="14" width="6" height="6" rx="1" /></>,
  profile: <><circle cx="12" cy="8" r="3.2" /><path d="M5 20c.7-3.3 3.2-5 7-5s6.3 1.7 7 5" /></>,
  visibility: <><path d="M2.5 12s3.3-6 9.5-6 9.5 6 9.5 6-3.3 6-9.5 6-9.5-6-9.5-6Z" /><circle cx="12" cy="12" r="2.5" /></>,
  assessment: <><path d="M7 3.5h7l4 4V20H7z" /><path d="M14 3.5V8h4M10 12h5M10 16h5" /></>,
  credentials: <><path d="M12 3 19 6v5c0 4.5-2.9 7.8-7 10-4.1-2.2-7-5.5-7-10V6z" /><path d="m9 12 2 2 4-4" /></>,
  tasks: <><path d="M8 5H5v15h14V5h-3" /><rect x="8" y="3" width="8" height="4" rx="1" /><path d="M9 12h6M9 16h6" /></>,
  invitations: <><path d="M4 6h16v12H4z" /><path d="m4.5 7 7.5 6 7.5-6" /></>,
  contacts: <><circle cx="9" cy="8" r="3" /><path d="M3.5 19c.5-3 2.3-4.5 5.5-4.5s5 1.5 5.5 4.5M16 6.5a3 3 0 0 1 0 5.8M17 15c2.1.5 3.3 1.8 3.5 4" /></>,
  company: <><path d="M4 20V5l8-2v17M12 8h8v12M2.5 20h19" /><path d="M7 7h2M7 11h2M7 15h2M15 11h2M15 15h2" /></>,
  needs: <><path d="M4 5h16v14H4z" /><path d="M8 9h8M8 13h5M8 17h3" /></>,
  matches: <><circle cx="10.5" cy="10.5" r="6" /><path d="m15 15 5 5M8 10.5h5M10.5 8v5" /></>,
  bank: <><ellipse cx="12" cy="6" rx="8" ry="3" /><path d="M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6" /></>,
  shortlists: <><path d="m12 3 2.7 5.5 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z" /></>,
  recruiting: <><circle cx="9" cy="8" r="3" /><path d="M3.5 20c.5-3.3 2.3-5 5.5-5s5 1.7 5.5 5M15 5h5M17.5 2.5v5M16 12h4" /></>,
  menu: <><path d="M4 6h16M4 12h16M4 18h16" /></>,
  logout: <><path d="M10 4H5v16h5M14 8l4 4-4 4M8 12h10" /></>,
};

export default function NavIcon({ name }: { name: NavIconName }) {
  return <svg aria-hidden="true" focusable="false" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}
