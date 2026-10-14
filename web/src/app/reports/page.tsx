'use client';

import Link from 'next/link';
import { useSession } from 'next-auth/react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AccessDenied } from '@/components/AccessDenied';
import { AppShell } from '@/components/AppShell';
import { ReportResultView } from '@/components/reports/ReportResultView';
import { ApiError, apiFetch, apiJson } from '@/lib/api';
import type { FolderNode, GroupRow, TagRow } from '@/lib/api';
import { canSeeFeature } from '@/lib/features';
import { usePermissions } from '@/lib/permissions';
import {
  EXPORT_FORMAT_LABELS,
  GRANULARITY_LABELS,
  QUICK_PERIOD_LABELS,
  defaultParams,
  flattenFolders,
  quickPeriod,
  suggestGranularity,
  validatePeriod,
} from '@/lib/reports';
import type {
  ExportFormat,
  Granularity,
  QuickPeriod,
  ReportParams,
  ReportResult,
  ReportTemplate,
  SavedReportDetail,
  SavedReportSummary,
} from '@/lib/reports';

const PAGE_SIZE = 50;

function inputClass(): string {
  return 'w-full border border-gray-300 rounded-md px-3 py-1.5 text-sm text-gray-800 bg-white';
}

function toggleId(list: string[], id: string): string[] {
  return list.includes(id) ? list.filter((x) => x !== id) : [...list, id];
}

function CheckList({
  legend,
  options,
  selected,
  onToggle,
}: {
  legend: string;
  options: { id: string; label: string }[];
  selected: string[];
  onToggle: (id: string) => void;
}) {
  return (
    <fieldset className="min-w-0">
      <legend className="text-xs font-semibold text-gray-500 mb-1">{legend}</legend>
      <div className="max-h-32 overflow-y-auto border border-gray-200 rounded-md p-2 space-y-1 bg-white">
        {options.length === 0 ? (
          <p className="text-xs text-gray-400">Không có lựa chọn.</p>
        ) : (
          options.map((o) => (
            <label key={o.id} className="flex items-center gap-2 text-sm text-gray-700">
              <input type="checkbox" checked={selected.includes(o.id)} onChange={() => onToggle(o.id)} />
              <span className="truncate">{o.label}</span>
            </label>
          ))
        )}
      </div>
    </fieldset>
  );
}

/** export-reporting (FR-30/FR-31): chọn mẫu, tham số, xem báo cáo, lưu bộ tham số, tạo tệp xuất. */
export default function ReportsPage() {
  const { data: session, status } = useSession();
  const perms = usePermissions();
  const canView = canSeeFeature(session, 'reports', perms);
  const canExport = perms.can('report.export');

  const [templates, setTemplates] = useState<ReportTemplate[] | null>(null);
  const [templateCode, setTemplateCode] = useState<string>('REPO_SUMMARY');
  const [params, setParams] = useState<ReportParams>(() => defaultParams(new Date()));
  const [groups, setGroups] = useState<GroupRow[]>([]);
  const [tags, setTags] = useState<TagRow[]>([]);
  const [folders, setFolders] = useState<FolderNode[]>([]);
  const [saved, setSaved] = useState<SavedReportSummary[]>([]);
  const [result, setResult] = useState<ReportResult | null>(null);
  const [page, setPage] = useState(0);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [saveName, setSaveName] = useState('');
  const [exportFormat, setExportFormat] = useState<ExportFormat>('PDF');
  const [exporting, setExporting] = useState(false);
  const [createdExportId, setCreatedExportId] = useState<string | null>(null);
  // Banners render at the top of the page while the Run/Export buttons sit below the parameter form: bring them
  // into view, otherwise a successful export or a 429 ("you already have N files being generated") goes unnoticed.
  // scroll-mt-24 keeps it clear of the 72px sticky AppShell header.
  const feedbackRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (error || info || createdExportId) {
      feedbackRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
    }
  }, [error, info, createdExportId]);

  const loadSaved = useCallback(() => {
    apiJson<SavedReportSummary[]>('/reports/saved', session)
      .then(setSaved)
      .catch((e: Error) => setError(e.message));
  }, [session]);

  useEffect(() => {
    if (!canView) return;
    apiJson<ReportTemplate[]>('/reports/templates', session)
      .then(setTemplates)
      .catch((e: Error) => setError(e.message));
    apiJson<GroupRow[]>('/groups', session).then(setGroups).catch(() => setGroups([]));
    apiJson<TagRow[]>('/tags', session).then(setTags).catch(() => setTags([]));
    apiJson<FolderNode[]>('/folders/tree', session).then(setFolders).catch(() => setFolders([]));
    loadSaved();
  }, [canView, session, loadSaved]);

  const folderOptions = useMemo(() => flattenFolders(folders), [folders]);
  const topicTags = useMemo(() => tags.filter((t) => t.kind === 'topic'), [tags]);
  const docTypeTags = useMemo(
    () => tags.filter((t) => t.kind === 'doc_type'),
    [tags],
  );

  // Fail-closed, but avoid flashing AccessDenied while /me/permissions is in flight.
  if (status === 'loading' || (session && perms.status === 'loading')) return null;
  if (!session || !canView) {
    return <AccessDenied featureKey="reports" />;
  }

  const template = templates?.find((t) => t.code === templateCode);

  const setPeriod = (from: string, to: string) =>
    setParams((p) => ({ ...p, from, to, granularity: suggestGranularity(from, to) }));

  const applyQuick = (kind: QuickPeriod) => {
    const { from, to } = quickPeriod(kind, new Date());
    setPeriod(from, to);
  };

  const run = async (targetPage = 0) => {
    const invalid = validatePeriod(params.from, params.to);
    if (invalid) {
      setError(invalid);
      return;
    }
    setRunning(true);
    setError(null);
    setInfo(null);
    try {
      const r = await apiJson<ReportResult>(`/reports/${templateCode}/run`, session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...params, page: targetPage, size: PAGE_SIZE }),
      });
      setResult(r);
      setPage(targetPage);
      // The result renders below a long form - bring it into view (next frame, once rendered).
      requestAnimationFrame(() => {
        const el = document.getElementById('report-result');
        if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
    } catch (e) {
      setResult(null);
      setError((e as Error).message);
    } finally {
      setRunning(false);
    }
  };

  const save = async () => {
    setError(null);
    setInfo(null);
    try {
      await apiJson('/reports/saved', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: saveName, templateCode, params }),
      });
      setSaveName('');
      setInfo('Đã lưu báo cáo.');
      loadSaved();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const openSaved = async (id: string) => {
    setError(null);
    setInfo(null);
    try {
      const d = await apiJson<SavedReportDetail>(`/reports/saved/${id}`, session);
      setTemplateCode(d.templateCode);
      setParams({
        ...d.params,
        groupIds: d.params.groupIds ?? [],
        topicTagIds: d.params.topicTagIds ?? [],
        docTypeTagIds: d.params.docTypeTagIds ?? [],
        folderId: d.params.folderId ?? null,
      });
      setResult(null);
      if (d.warnings.length > 0) setInfo(d.warnings.join(' '));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const renameSaved = async (s: SavedReportSummary) => {
    const name = window.prompt('Tên mới cho báo cáo đã lưu', s.name);
    if (name === null) return;
    try {
      await apiFetch(`/reports/saved/${s.id}`, session, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      });
      loadSaved();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const deleteSaved = async (s: SavedReportSummary) => {
    if (!window.confirm(`Xoá báo cáo đã lưu "${s.name}"?`)) return;
    try {
      await apiFetch(`/reports/saved/${s.id}`, session, { method: 'DELETE' });
      loadSaved();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const startExport = async () => {
    const invalid = validatePeriod(params.from, params.to);
    if (invalid) {
      setError(invalid);
      return;
    }
    setExporting(true);
    setError(null);
    setInfo(null);
    setCreatedExportId(null);
    try {
      const r = await apiJson<{ exportId: string }>('/reports/exports', session, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ templateCode, params, format: exportFormat }),
      });
      setCreatedExportId(r.exportId);
    } catch (e) {
      // 429 carries a self-explanatory message (per-user vs system busy) - show it as is.
      setError(e instanceof ApiError ? e.message : (e as Error).message);
    } finally {
      setExporting(false);
    }
  };

  return (
    <AppShell title="Báo cáo">
      <div className="max-w-7xl space-y-6">
        <div className="flex items-start justify-between">
          <div>
            <h2 className="text-2xl font-bold text-[#0D2546]">Báo cáo thống kê</h2>
            <p className="text-sm text-gray-500 mt-1">
              Số liệu chính xác trong phạm vi truy cập của bạn (khác với số kết quả ước tính trên
              màn tìm kiếm).
            </p>
          </div>
          {canExport && (
            <Link href="/reports/exports" className="text-sm font-semibold text-[#0D2546] hover:text-[#D11A2A]">
              Tệp đã xuất →
            </Link>
          )}
        </div>

        <div ref={feedbackRef} className="space-y-3 empty:hidden scroll-mt-24">
          {error && (
            <div role="alert" className="bg-red-50 border border-red-200 rounded-md p-3 text-sm text-red-800">
              {error}
            </div>
          )}
          {info && (
            <div role="status" className="bg-blue-50 border border-blue-200 rounded-md p-3 text-sm text-blue-800">
              {info}
            </div>
          )}
          {createdExportId && (
            <div role="status" className="bg-green-50 border border-green-200 rounded-md p-3 text-sm text-green-800">
              Đã tạo yêu cầu xuất tệp. Theo dõi tiến độ và tải tệp tại{' '}
              <Link href="/reports/exports" className="font-semibold underline">
                Tệp đã xuất
              </Link>
              .
            </div>
          )}
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <div className="lg:col-span-2 space-y-4">
            <section className="bg-white border border-gray-200 rounded-lg p-4 space-y-3" aria-label="Chọn mẫu">
              <h3 className="text-sm font-semibold text-[#0D2546]">1. Chọn mẫu báo cáo</h3>
              {templates === null ? (
                <p className="text-sm text-gray-400">Đang tải…</p>
              ) : (
                <div className="space-y-2">
                  {templates.map((t) => (
                    <label
                      key={t.code}
                      className={`block border rounded-md p-3 cursor-pointer ${
                        templateCode === t.code ? 'border-[#0D2546] bg-blue-50' : 'border-gray-200'
                      }`}
                    >
                      <input
                        type="radio"
                        name="template"
                        className="mr-2"
                        checked={templateCode === t.code}
                        onChange={() => {
                          setTemplateCode(t.code);
                          setResult(null);
                        }}
                      />
                      <span className="font-semibold text-sm text-gray-800">{t.name}</span>
                      <span className="block text-xs text-gray-500 ml-5">{t.description}</span>
                    </label>
                  ))}
                </div>
              )}
            </section>

            <section className="bg-white border border-gray-200 rounded-lg p-4 space-y-4" aria-label="Tham số">
              <h3 className="text-sm font-semibold text-[#0D2546]">2. Tham số</h3>
              <div className="flex flex-wrap gap-2" role="group" aria-label="Chọn nhanh kỳ">
                {(Object.keys(QUICK_PERIOD_LABELS) as QuickPeriod[]).map((k) => (
                  <button
                    key={k}
                    type="button"
                    onClick={() => applyQuick(k)}
                    className="px-3 py-1 text-xs font-semibold border border-gray-300 rounded-full text-gray-700 hover:bg-gray-50 cursor-pointer"
                  >
                    {QUICK_PERIOD_LABELS[k]}
                  </button>
                ))}
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <label className="text-xs font-semibold text-gray-500">
                  Từ ngày
                  <input
                    type="date"
                    className={inputClass()}
                    value={params.from}
                    onChange={(e) => setPeriod(e.target.value, params.to)}
                  />
                </label>
                <label className="text-xs font-semibold text-gray-500">
                  Đến ngày
                  <input
                    type="date"
                    className={inputClass()}
                    value={params.to}
                    onChange={(e) => setPeriod(params.from, e.target.value)}
                  />
                </label>
                <label className="text-xs font-semibold text-gray-500">
                  Độ chi tiết
                  <select
                    className={inputClass()}
                    value={params.granularity}
                    onChange={(e) => setParams((p) => ({ ...p, granularity: e.target.value as Granularity }))}
                  >
                    {(Object.keys(GRANULARITY_LABELS) as Granularity[]).map((g) => (
                      <option key={g} value={g}>
                        {GRANULARITY_LABELS[g]}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <CheckList
                  legend="Nhóm chia sẻ"
                  options={groups.map((g) => ({ id: g.id, label: g.name }))}
                  selected={params.groupIds}
                  onToggle={(id) => setParams((p) => ({ ...p, groupIds: toggleId(p.groupIds, id) }))}
                />
                <label className="text-xs font-semibold text-gray-500">
                  Thư mục (gồm thư mục con)
                  <select
                    className={inputClass()}
                    value={params.folderId ?? ''}
                    onChange={(e) => setParams((p) => ({ ...p, folderId: e.target.value || null }))}
                  >
                    <option value="">Tất cả</option>
                    {folderOptions.map((f) => (
                      <option key={f.id} value={f.id}>
                        {f.label}
                      </option>
                    ))}
                  </select>
                </label>
                <CheckList
                  legend="Chủ đề"
                  options={topicTags.map((t) => ({ id: t.id, label: t.label }))}
                  selected={params.topicTagIds}
                  onToggle={(id) => setParams((p) => ({ ...p, topicTagIds: toggleId(p.topicTagIds, id) }))}
                />
                <CheckList
                  legend="Loại tài liệu"
                  options={docTypeTags.map((t) => ({ id: t.id, label: t.label }))}
                  selected={params.docTypeTagIds}
                  onToggle={(id) => setParams((p) => ({ ...p, docTypeTagIds: toggleId(p.docTypeTagIds, id) }))}
                />
              </div>
              <div className="flex flex-wrap items-center gap-3 pt-2 border-t border-gray-100">
                <button
                  type="button"
                  onClick={() => void run(0)}
                  disabled={running || !template}
                  className="bg-[#0D2546] hover:bg-[#163a6b] disabled:bg-gray-300 text-white font-semibold px-4 py-1.5 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                >
                  {running ? 'Đang chạy…' : 'Chạy báo cáo'}
                </button>
                {canExport && (
                  <span className="flex items-center gap-2">
                    <select
                      aria-label="Định dạng tệp"
                      className="border border-gray-300 rounded-md px-2 py-1.5 text-sm"
                      value={exportFormat}
                      onChange={(e) => setExportFormat(e.target.value as ExportFormat)}
                    >
                      {(Object.keys(EXPORT_FORMAT_LABELS) as ExportFormat[]).map((f) => (
                        <option key={f} value={f}>
                          {EXPORT_FORMAT_LABELS[f]}
                        </option>
                      ))}
                    </select>
                    <button
                      type="button"
                      onClick={() => void startExport()}
                      disabled={exporting || !template}
                      className="border border-[#0D2546] text-[#0D2546] hover:bg-blue-50 disabled:text-gray-300 disabled:border-gray-300 font-semibold px-4 py-1.5 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                    >
                      {exporting ? 'Đang gửi…' : 'Xuất tệp'}
                    </button>
                  </span>
                )}
                <span className="flex items-center gap-2 ml-auto">
                  <input
                    aria-label="Tên báo cáo đã lưu"
                    className="border border-gray-300 rounded-md px-3 py-1.5 text-sm"
                    placeholder="Tên báo cáo để lưu"
                    maxLength={100}
                    value={saveName}
                    onChange={(e) => setSaveName(e.target.value)}
                  />
                  <button
                    type="button"
                    onClick={() => void save()}
                    disabled={saveName.trim() === ''}
                    className="border border-gray-300 text-gray-700 hover:bg-gray-50 disabled:text-gray-300 font-semibold px-3 py-1.5 rounded-md text-sm cursor-pointer disabled:cursor-not-allowed"
                  >
                    Lưu
                  </button>
                </span>
              </div>
            </section>
          </div>

          <aside className="bg-white border border-gray-200 rounded-lg p-4 h-fit" aria-label="Báo cáo đã lưu">
            <h3 className="text-sm font-semibold text-[#0D2546] mb-3">Báo cáo đã lưu</h3>
            {saved.length === 0 ? (
              <p className="text-sm text-gray-400">Chưa có báo cáo nào được lưu.</p>
            ) : (
              <ul className="space-y-2">
                {saved.map((s) => (
                  <li key={s.id} className="border border-gray-100 rounded-md p-2 text-sm">
                    <div className="font-semibold text-gray-800 truncate" title={s.name}>
                      {s.name}
                    </div>
                    <div className="text-xs text-gray-400">{s.templateCode}</div>
                    <div className="flex gap-3 mt-1 text-xs font-semibold">
                      <button type="button" className="text-[#0D2546] cursor-pointer" onClick={() => void openSaved(s.id)}>
                        Mở
                      </button>
                      <button type="button" className="text-gray-600 cursor-pointer" onClick={() => void renameSaved(s)}>
                        Đổi tên
                      </button>
                      <button type="button" className="text-[#D11A2A] cursor-pointer" onClick={() => void deleteSaved(s)}>
                        Xoá
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </aside>
        </div>

        <div id="report-result" className="scroll-mt-24">
          {result && <ReportResultView result={result} page={page} pageSize={PAGE_SIZE} onPage={(p) => void run(p)} />}
        </div>
      </div>
    </AppShell>
  );
}
