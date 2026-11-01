import { Link2, Plus, RefreshCw, Trash2 } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { Button } from "@/components/common/Button";
import { Toggle } from "@/components/common/Toggle";
import { Tr, t } from "@/i18n";
import { dropLink, keepLink, refreshLink, useCssLinks, type LinkState } from "@/lib/look/cssLinks";
import { useLookDraft } from "@/lib/look/draft";
import { fetchFailure } from "@/lib/look/fetchText";
import { byteLength, packText, unpackText, UnreadableText } from "@/lib/look/pack";
import { useSafeMode } from "@/lib/look/safeMode";
import { MAX_LINKS, MAX_SNIPPET_BYTES, linkName, newLookId, sanitizeCustomCss } from "@/lib/look/sanitize";
import type { CssLink } from "@/lib/look/types";
import { formatBytes, formatRelative } from "@/lib/time";
import { useSettings } from "@/stores/settings";
import { useHoldUnsaved, useUnsaved } from "@/stores/unsaved";
import { useUI } from "@/stores/ui";
import { AddCssLinkDialog } from "./look/AddCssLinkDialog";
import { Caution } from "./look/Caution";
import { GuideLink } from "./look/GuideLink";
import { CSS_GUIDE_URL } from "@/lib/legal";
import { CodeEditor } from "./look/CodeEditor";
import look from "./look/Look.module.css";
import s from "./Settings.module.css";

const EXAMPLE = ":root {\n  --brand: #e91e63;\n}\n";

function linkStatus(state: LinkState | undefined): { text: string; kind: "ok" | "error" | null } {
  if (!state || state.status === "loading") return { text: t("look.css.loading"), kind: null };
  const failed = state.error ? fetchFailure(state.error, state.errorStatus) : null;
  if (state.status === "error") return { text: failed ?? t("look.fetch.network"), kind: "error" };
  const kept = t("look.css.kept", { size: formatBytes(state.size ?? 0), when: formatRelative(state.fetchedAt ?? Date.now()) });
  return failed ? { text: `${kept} ${t("look.css.refreshFailed", { reason: failed })}`, kind: "error" } : { text: kept, kind: "ok" };
}

/**
 * Custom CSS: the person's own snippet, drawn over the whole app as it is
 * typed, and themes loaded from links. Both follow the account; the links'
 * files are kept on each device.
 */
export default function CustomCssPage() {
  const raw = useSettings((x) => x.settings.customCss);
  const patch = useSettings((x) => x.patch);
  const cc = useMemo(() => sanitizeCustomCss(raw), [raw]);
  const mode = useSafeMode((m) => m.mode);
  const links = useCssLinks((l) => l.byUrl);
  const toast = useUI((u) => u.toast);
  const setDraft = useLookDraft((d) => d.setCss);
  const [saved, setSaved] = useState<string | null>(null);
  const [text, setText] = useState<string | null>(null);
  const [unreadable, setUnreadable] = useState(false);
  const [busy, setBusy] = useState(false);
  const [adding, setAdding] = useState(false);

  // The saved snippet; typing that is not saved yet stays when another device saves its own.
  useEffect(() => {
    let live = true;
    unpackText(cc.snippet).then(
      (v) => {
        if (!live) return;
        setSaved(v);
        setText((cur) => (cur === null || cur === saved ? v : cur));
      },
      (err: unknown) => {
        if (!live) return;
        setUnreadable(err instanceof UnreadableText);
        setSaved("");
        setText((cur) => cur ?? "");
      },
    );
    return () => {
      live = false;
    };
    // saved is read as it was when this snippet arrived
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cc.snippet]);

  const dirty = text !== null && saved !== null && text !== saved;
  const size = byteLength(text ?? "");
  const over = size > MAX_SNIPPET_BYTES;

  // the whole app shows what is typed, while this page is open
  useEffect(() => {
    setDraft(dirty && cc.snippetOn && !over ? text : null);
  }, [dirty, text, cc.snippetOn, over, setDraft]);
  useEffect(() => () => setDraft(null), [setDraft]);

  useHoldUnsaved(dirty);
  const nudge = useUnsaved((u) => u.nudge);
  const [calm] = useState(nudge);

  const save = async () => {
    if (text === null || over) return;
    setBusy(true);
    try {
      patch({ customCss: { ...cc, snippet: await packText(text) } });
      setSaved(text);
      toast(t("look.css.saved"), "success");
    } finally {
      setBusy(false);
    }
  };

  const setLinks = (next: CssLink[]) => patch({ customCss: { ...cc, links: next } });
  const removeLink = (link: CssLink) => {
    const rest = cc.links.filter((l) => l.id !== link.id);
    setLinks(rest);
    void dropLink(link.url, rest.some((l) => l.url === link.url));
  };

  return (
    <>
      <h2 data-setting="css" className={s.title}>{t("look.css.title")}</h2>
      <Caution testId="css-caution">
        <p>
          <strong>{t("look.css.cautionLead")}</strong> {t("look.css.cautionBody")}
        </p>
        <p>{t("look.safe.howTo")}</p>
      </Caution>
      <GuideLink title={t("look.css.guideTitle")} body={t("look.css.guideBody")} href={CSS_GUIDE_URL} testId="css-guide" />
      {mode === "manual" ? (
        <div className={look.notice} role="status">
          <p className={look.noticeBody}>{t("look.css.offInSafeMode")}</p>
        </div>
      ) : null}

      <div className={look.sectionHead}>
        <h3 className={s.h5}>{t("look.css.yours")}</h3>
      </div>
      <Toggle checked={cc.snippetOn} onChange={(on) => patch({ customCss: { ...cc, snippetOn: on } })} label={t("look.css.useMine")} description={t("look.css.useMineHint")} />
      <div style={{ marginTop: 12 }}>
        {unreadable ? <p className={s.hint} style={{ color: "var(--text-danger)" }}>{t("look.css.unreadable")}</p> : null}
        <CodeEditor value={text ?? ""} onChange={setText} label={t("look.css.editorLabel")} placeholder={EXAMPLE} testId="css-snippet" />
        <div className={look.editorFoot}>
          <span data-over={over ? "" : undefined} data-testid="css-size">
            {t("look.css.size", { size: formatBytes(size), max: formatBytes(MAX_SNIPPET_BYTES) })}
          </span>
          <span>{cc.snippetOn ? t("look.css.livePreview") : t("look.css.offPreview")}</span>
        </div>
        {over ? <p className={s.hint} style={{ color: "var(--text-danger)" }}>{t("look.css.tooBig", { max: formatBytes(MAX_SNIPPET_BYTES) })}</p> : null}
        <p className={s.hint} style={{ marginTop: 10 }}>
          <Tr id="look.css.tips" values={{ example: <code className={look.inlineCode}>{":root { --brand: #e91e63; }"}</code>, selector: <code className={look.inlineCode}>{'[class*="_messageList_"]'}</code> }} />
        </p>
      </div>

      <div className={look.sectionHead}>
        <h3 className={s.h5}>{t("look.css.links")}</h3>
        {cc.links.length < MAX_LINKS ? (
          <Button size="sm" variant="secondary" onClick={() => setAdding(true)} data-testid="css-link-open">
            <Plus size={15} aria-hidden="true" /> {t("look.css.addLink")}
          </Button>
        ) : null}
      </div>
      <p className={s.hint} style={{ margin: "0 0 12px" }}>
        {t("look.css.linksHint")}
      </p>
      {cc.links.length === 0 ? (
        <div className={look.empty}>{t("look.css.noLinks")}</div>
      ) : (
        <div className={look.list}>
          {cc.links.map((link) => {
            const state = links[link.url];
            const st = linkStatus(state);
            return (
              <div key={link.id} className={look.item} data-testid="css-link">
                <div className={look.itemTop}>
                  <div className={look.itemMain}>
                    <div className={look.itemTitle}>
                      <Link2 size={15} aria-hidden="true" style={{ flex: "none", color: "var(--icon)", alignSelf: "center" }} />
                      <span dir="auto">{linkName(link.url)}</span>
                    </div>
                    <span className={look.itemUrl} title={link.url}>
                      {link.url}
                    </span>
                  </div>
                  <div className={look.itemSwitch}>
                    <Toggle checked={link.enabled} onChange={(on) => setLinks(cc.links.map((l) => (l.id === link.id ? { ...l, enabled: on } : l)))} ariaLabel={t("look.css.useLink", { name: linkName(link.url) })} />
                  </div>
                </div>
                <div className={look.itemSub} data-kind={st.kind ?? undefined} style={st.kind === "error" ? { color: "var(--text-danger)" } : undefined} data-testid="css-link-status">
                  {st.text}
                </div>
                <div className={look.itemActions}>
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() =>
                      void refreshLink(link.url).then(() => {
                        const now = useCssLinks.getState().byUrl[link.url];
                        if (now?.error) toast(fetchFailure(now.error, now.errorStatus), "error");
                        else toast(t("look.css.refreshed", { name: linkName(link.url) }), "success");
                      })
                    }
                    loading={state?.status === "loading"}
                    data-testid="css-link-refresh"
                  >
                    <RefreshCw size={14} aria-hidden="true" /> {t("look.css.refresh")}
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => removeLink(link)} data-testid="css-link-remove">
                    <Trash2 size={14} aria-hidden="true" /> {t("look.css.remove")}
                  </Button>
                </div>
              </div>
            );
          })}
        </div>
      )}
      {adding ? (
        <AddCssLinkDialog
          taken={cc.links.map((l) => l.url)}
          onClose={() => setAdding(false)}
          onAdded={(url, css) => {
            void keepLink(url, css);
            setLinks([...cc.links, { id: newLookId(), url, enabled: true, addedAt: Date.now() }]);
            setAdding(false);
            toast(t("look.css.linkAdded", { name: linkName(url) }), "success");
          }}
        />
      ) : null}

      {dirty ? (
        <div key={nudge} className={[s.saveBar, nudge !== calm ? s.shake : ""].join(" ")} role="status" data-testid="save-bar">
          <span className={s.saveText}>{t("look.css.unsaved")}</span>
          <span className={[s.saveBtns, look.touchBar].join(" ")}>
            <Button variant="ghost" onClick={() => setText(saved)} disabled={busy}>
              {t("settings.themeBuilder.reset")}
            </Button>
            <Button variant="positive" onClick={() => void save()} loading={busy} disabled={over} data-testid="save">
              {t("look.css.save")}
            </Button>
          </span>
        </div>
      ) : null}
    </>
  );
}
