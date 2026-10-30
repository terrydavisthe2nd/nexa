// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { DisplaySourceRequest, EvilionDesktop } from "@/lib/desktopBridge";
import { useUI } from "@/stores/ui";
import { DisplayPicker, ScreenPermission } from "./DisplayPicker";

afterEach(() => {
  cleanup();
  delete (window as Window & { evilionDesktop?: EvilionDesktop }).evilionDesktop;
});

const request: DisplaySourceRequest = {
  requestId: "r1",
  audio: false,
  sources: [
    { id: "screen:1", name: "Entire screen", kind: "screen", thumbnail: "" },
    { id: "window:1", name: "Mail", kind: "window", thumbnail: "" },
    { id: "window:2", name: "Editor", kind: "window", thumbnail: "" },
  ],
};

const share = () => screen.getByTestId("display-share") as HTMLButtonElement;
const tabs = () => screen.getAllByRole("tab");

describe("DisplayPicker", () => {
  it("never shares the screen picked on the other tab", () => {
    const onChoose = vi.fn();
    render(<DisplayPicker request={request} onChoose={onChoose} />);
    // one screen: it starts picked, so Share is ready
    expect(share().disabled).toBe(false);
    fireEvent.click(tabs()[1]!);
    // on Windows nothing is picked yet, so there is nothing to share
    expect(share().disabled).toBe(true);
    fireEvent.click(share());
    expect(onChoose).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("Editor").closest("button")!);
    fireEvent.click(share());
    expect(onChoose).toHaveBeenCalledWith("window:2", false, null);
  });

  it("keeps each tab's pick, and gives the Windows tab a stop for the keyboard", () => {
    render(<DisplayPicker request={request} onChoose={vi.fn()} />);
    fireEvent.click(tabs()[1]!);
    const radios = screen.getAllByRole("radio");
    // nothing picked here: the first window is the group's one tab stop
    expect(radios.map((r) => r.tabIndex)).toEqual([0, -1]);
    // arrows move the pick, as a radio group does
    fireEvent.keyDown(radios[0]!, { key: "ArrowRight" });
    expect(screen.getAllByRole("radio").map((r) => r.getAttribute("aria-checked"))).toEqual(["false", "true"]);
    fireEvent.keyDown(screen.getAllByRole("radio")[1]!, { key: "ArrowRight" });
    expect(screen.getAllByRole("radio").map((r) => r.getAttribute("aria-checked"))).toEqual(["true", "false"]);
    // back on Screens, the screen is still the pick
    fireEvent.click(tabs()[0]!);
    expect(screen.getAllByRole("radio")[0]!.getAttribute("aria-checked")).toBe("true");
  });

  it("shares the focused source on Enter", () => {
    const onChoose = vi.fn();
    render(<DisplayPicker request={request} onChoose={onChoose} />);
    fireEvent.click(tabs()[1]!);
    fireEvent.keyDown(screen.getAllByRole("radio")[1]!, { key: "Enter" });
    expect(onChoose).toHaveBeenCalledWith("window:2", false, null);
  });
});

describe("ScreenPermission", () => {
  /** A desktop app with what the dialog uses; the app after 1.2.2 can also reset the permission. */
  function app(reset?: () => Promise<boolean>) {
    const bridge = { openScreenSettings: vi.fn(async () => {}), restartApp: vi.fn(async () => {}), ...(reset ? { resetScreenAccess: vi.fn(reset) } : {}) };
    (window as Window & { evilionDesktop?: unknown }).evilionDesktop = bridge;
    return bridge;
  }
  const button = (id: string) => screen.queryByTestId(id);

  it("resets a permission left by an older copy, then says what is left to do", async () => {
    const bridge = app(async () => true);
    render(<ScreenPermission stale onClose={vi.fn()} />);
    expect(screen.getByText(/Choose Reset Permission/)).toBeTruthy();
    expect(button("restart-for-screen")).toBeNull();
    await act(async () => fireEvent.click(button("reset-screen-access")!));
    expect(bridge.resetScreenAccess).toHaveBeenCalledOnce();
    expect(screen.getByText("The old permission is cleared.")).toBeTruthy();
    expect(button("reset-screen-access")).toBeNull();
    fireEvent.click(button("restart-for-screen")!);
    expect(bridge.restartApp).toHaveBeenCalledOnce();
  });

  it("gives the steps to do it by hand when macOS will not reset it", async () => {
    useUI.setState({ toasts: [] });
    app(async () => false);
    render(<ScreenPermission stale onClose={vi.fn()} />);
    await act(async () => fireEvent.click(button("reset-screen-access")!));
    expect(useUI.getState().toasts.map((x) => [x.text, x.kind])).toEqual([[expect.stringContaining("with the minus button"), "error"]]);
    expect(button("reset-screen-access")).toBeTruthy();
  });

  it("offers the reset beside Open Settings when macOS simply says no", () => {
    const bridge = app(async () => true);
    render(<ScreenPermission stale={false} onClose={vi.fn()} />);
    expect(screen.getByText(/If Nexa is already on there/)).toBeTruthy();
    fireEvent.click(button("open-screen-settings")!);
    expect(bridge.openScreenSettings).toHaveBeenCalledOnce();
    expect(button("reset-screen-access")).toBeTruthy();
  });

  it("leaves an app that cannot reset with Open Settings, Restart and the steps by hand", () => {
    app();
    render(<ScreenPermission stale onClose={vi.fn()} />);
    expect(button("reset-screen-access")).toBeNull();
    expect(button("open-screen-settings")).toBeTruthy();
    expect(button("restart-for-screen")).toBeTruthy();
    expect(screen.getByText(/remove Nexa with the minus button/)).toBeTruthy();
  });
});
