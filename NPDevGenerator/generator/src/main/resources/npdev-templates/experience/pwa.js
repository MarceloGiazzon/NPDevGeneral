// Installable experiences (FORM P5-T7). index.html is one file shared by every experience, so it cannot name the manifest;
// once GET /api/x/me says which experience this is, the head gets the manifest link, the theme colour and the iPhone
// home-screen hints. The server answers /x/<name>/manifest.webmanifest and the icons (ExperienceController).
//
// Deliberately NO service worker and NO offline writes here: "installable" means the manifest + icons only. Working
// offline (queued writes, a cached shell) is MOB-2's area; do not grow this file into it.

const FALLBACK_COLOR = "#0b5fff"; // shell.css's default --np-accent, the same default ManifestBuilder uses

/** Only experiences meant for a phone are installable; `device` defaults to "both" (the schema default). */
export const isInstallable = (exp) => !!exp && !!exp.name && (exp.device == null || exp.device === "mobile" || exp.device === "both");

// The generator resolved `themeColor` (the experience's theme, else the shell default) into the experience, so the
// meta tag, the manifest and the icons always agree.
const accent = (exp) => (/^#[0-9a-fA-F]{6}$/.test(String(exp.themeColor || "")) ? exp.themeColor : FALLBACK_COLOR);

/** Adds the manifest link, theme-color and apple-* tags to <head>. Returns the elements added ([] when not installable). */
export function addInstallable(doc, exp) {
  if (!isInstallable(exp)) return [];
  const head = doc.head || doc.documentElement;
  const base = "/x/" + encodeURIComponent(exp.name) + "/";
  const add = (tag, attrs) => {
    const el = doc.createElement(tag);
    for (const [key, value] of Object.entries(attrs)) el.setAttribute(key, value);
    head.appendChild(el);
    return el;
  };
  return [
    add("link", { rel: "manifest", href: base + "manifest.webmanifest" }),
    add("meta", { name: "theme-color", content: accent(exp) }),
    add("meta", { name: "apple-mobile-web-app-capable", content: "yes" }),
    add("link", { rel: "apple-touch-icon", href: base + "icon-192.png" })
  ];
}
