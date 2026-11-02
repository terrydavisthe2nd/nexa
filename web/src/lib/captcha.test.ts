// @vitest-environment jsdom
import { api } from "@/api/client";
import { captchaToken, loadCaptchaConfig } from "./captcha";

type Opts = Record<string, (arg?: string) => void>;

// the script's onload, which says the API is ready (jsdom never runs the script)
const ready = (): void => (window as unknown as { nexaTurnstileReady?: () => void }).nexaTurnstileReady?.();

describe("the captcha sheet", () => {
  let rendered: Opts | null = null;
  beforeEach(async () => {
    rendered = null;
    api.request = <T>(): Promise<T> => Promise.resolve({ captcha: { provider: "turnstile", site_key: "k" } } as T);
    window.turnstile = {
      render: (_el: HTMLElement, opts: Record<string, unknown>) => {
        rendered = opts as Opts;
        return "w1";
      },
      remove: () => undefined,
    };
    await loadCaptchaConfig();
  });
  const rendering = (): Promise<void> =>
    vi.waitFor(() => {
      ready();
      expect(rendered).not.toBeNull();
    });
  afterEach(() => {
    delete window.turnstile;
    document.body.innerHTML = "";
  });

  it("is a modal dialog in the reader's language, takes focus when it shows and gives it back", async () => {
    const button = document.createElement("button");
    document.body.append(button);
    button.focus();
    const token = captchaToken("login");
    await rendering();
    const dialog = document.querySelector<HTMLElement>("[role=dialog]")!;
    expect(dialog.getAttribute("aria-modal")).toBe("true");
    expect(dialog.getAttribute("aria-label")).toBe("Captcha");
    rendered!["before-interactive-callback"]!();
    expect(dialog.contains(document.activeElement)).toBe(true);
    rendered!["callback"]!("tok");
    await expect(token).resolves.toBe("tok");
    expect(document.activeElement).toBe(button);
    expect(document.querySelector("[role=dialog]")).toBeNull();
  });

  it("closes on Escape, as every other sheet in the app does", async () => {
    const token = captchaToken("login");
    await rendering();
    rendered!["before-interactive-callback"]!();
    document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    await expect(token).rejects.toMatchObject({ code: "captcha_cancelled" });
    expect(document.querySelector("[role=dialog]")).toBeNull();
  });

  it("is cancelled with its Cancel button, and asks Turnstile for the action", async () => {
    const token = captchaToken("login");
    await rendering();
    expect(rendered!["action"]).toBe("login");
    rendered!["before-interactive-callback"]!();
    document.querySelector<HTMLButtonElement>(".captcha-cancel")!.click();
    await expect(token).rejects.toMatchObject({ code: "captcha_cancelled" });
  });

  it("never stacks: the same action pressed again while its captcha is up gets none", async () => {
    const first = captchaToken("sticker_pack_public");
    const again = captchaToken("sticker_pack_public");
    await expect(again).rejects.toMatchObject({ code: "captcha_pending" });
    await rendering();
    expect(document.querySelectorAll("[role=dialog]")).toHaveLength(1);
    rendered!["callback"]!("tok");
    await expect(first).resolves.toBe("tok");
    // once it is done, the action can ask again
    rendered = null;
    const later = captchaToken("sticker_pack_public");
    await rendering();
    rendered!["callback"]!("tok2");
    await expect(later).resolves.toBe("tok2");
  });

  it("shows one at a time: another action's waits for the first, and cancelling one cancels those waiting", async () => {
    const first = captchaToken("join_server");
    const second = captchaToken("friend_request");
    await rendering();
    expect(document.querySelectorAll("[role=dialog]")).toHaveLength(1);
    const one = rendered!;
    rendered = null;
    one["callback"]!("tok1");
    await expect(first).resolves.toBe("tok1");
    // the second gets a token of its own once the first is gone
    await rendering();
    expect(document.querySelectorAll("[role=dialog]")).toHaveLength(1);
    rendered!["callback"]!("tok2");
    await expect(second).resolves.toBe("tok2");

    rendered = null;
    const third = captchaToken("join_server");
    const fourth = captchaToken("friend_request");
    await rendering();
    rendered!["before-interactive-callback"]!();
    document.querySelector<HTMLButtonElement>(".captcha-cancel")!.click();
    await expect(third).rejects.toMatchObject({ code: "captcha_cancelled" });
    await expect(fourth).rejects.toMatchObject({ code: "captcha_cancelled" });
    expect(document.querySelector("[role=dialog]")).toBeNull();
    // a captcha asked for after that shows as usual
    rendered = null;
    const fifth = captchaToken("join_server");
    await rendering();
    rendered!["callback"]!("tok5");
    await expect(fifth).resolves.toBe("tok5");
  });

  it("uses Turnstile's key when the server still names hCaptcha for older apps", async () => {
    api.request = <T>(): Promise<T> => Promise.resolve({ captcha: { provider: "hcaptcha", site_key: "h" }, turnstile: { site_key: "t" } } as T);
    await loadCaptchaConfig();
    const token = captchaToken("login");
    await rendering();
    expect(rendered!["sitekey"]).toBe("t");
    rendered!["callback"]!("tok");
    await expect(token).resolves.toBe("tok");
  });
});
