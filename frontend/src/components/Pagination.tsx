import type { Page } from '../api/types';

export function Pagination<T>({ page, onChange, busy }: { page: Page<T>; onChange: (p: number) => void; busy?: boolean }) {
  if (page.totalElements === 0) return null;
  const from = page.page * page.size + 1;
  const to = Math.min(page.totalElements, from + page.content.length - 1);
  return (
    <div className="pager">
      <span className="num">
        {from}–{to} of {page.totalElements}
        {busy && <span className="spinner" style={{ marginLeft: 8, width: 11, height: 11 }} aria-label="Loading" />}
      </span>
      <div className="row">
        <button className="btn btn-sm" disabled={page.page === 0} onClick={() => onChange(page.page - 1)}>
          Previous
        </button>
        <button className="btn btn-sm" disabled={page.page + 1 >= page.totalPages} onClick={() => onChange(page.page + 1)}>
          Next
        </button>
      </div>
    </div>
  );
}
