// @vitest-environment jsdom
import { createElement, type ReactNode } from "react";
import { render } from "@testing-library/react";
import type { Channel, PublicUser } from "@/api/types";

const api = vi.hoisted(() => ({ patch: vi.fn(), removeRecipient: vi.fn() }));
vi.mock("@/api/channels", () => ({ channels: { patch: api.patch, removeRecipient: api.removeRecipient } }));

import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useRelationships } from "@/stores/relationships";
import { useUsers } from "@/stores/users";
import { groupMemberMenu } from "./groupMemberMenu";

function textOf(node: ReactNode): string {
  const view = render(createElement("div", null, node));
  const text = view.container.textContent ?? "";
  view.unmount();
  return text;
}

const ADMIN = 1;
const group = { id: "g", type: "group_dm", parent_id: null, owner_id: "owner", name: "Crew", recipient_ids: ["owner", "me", "bob"] } as unknown as Channel;

function signIn(flags = 0): void {
  useAuth.setState({ me: { id: "me", display_name: "Me", flags } as never });
}

function ids(userId: string): string[] {
  return groupMemberMenu.items({ channelId: "g", userId: userId, nav: vi.fn(), openModal: vi.fn() }).map((i) => i.id);
}

describe("groupMemberMenuItems", () => {
  beforeEach(() => {
    useChannels.setState({ byId: { g: group } });
    useRelationships.setState({ byUser: {} });
    useUsers.setState({
      byId: {
        owner: { id: "owner", username: "olive", display_name: "Olive", flags: 0 } as PublicUser,
        bob: { id: "bob", username: "bob", display_name: "Bob", flags: 0 } as PublicUser,
      },
    });
  });

  it("gives a member the profile, a mention, a message and the friend and block choices, and nothing over others", () => {
    signIn();
    expect(ids("bob")).toEqual(["profile", "mention", "message", "add-friend", "block", "copy-profile-link"]);
    expect(ids("owner")).toEqual(["profile", "mention", "message", "add-friend", "block", "copy-profile-link"]);
    expect(ids("me")).toEqual(["profile", "mention", "leave"]);
  });

  it("lets the owner hand the group on and remove people", () => {
    useChannels.setState({ byId: { g: { ...group, owner_id: "me" } } });
    signIn();
    expect(ids("bob")).toEqual(["profile", "mention", "message", "add-friend", "block", "owner", "remove", "copy-profile-link"]);
    // leaving is still how the owner goes
    expect(ids("me")).toEqual(["profile", "mention", "leave"]);
  });

  it("gives a Nexa admin the owner's actions, short of removing the owner", () => {
    signIn(ADMIN);
    expect(ids("bob")).toEqual(["profile", "mention", "message", "add-friend", "block", "owner", "remove", "copy-profile-link"]);
    expect(ids("owner")).toEqual(["profile", "mention", "message", "add-friend", "block", "copy-profile-link"]);
    const mine = groupMemberMenu.items({ channelId: "g", userId: "me", nav: vi.fn(), openModal: vi.fn() });
    expect(mine.find((i) => i.id === "owner")?.label).toBe("Take Ownership");
  });

  it("asks before removing anyone", () => {
    signIn(ADMIN);
    const openModal = vi.fn();
    groupMemberMenu.items({ channelId: "g", userId: "bob", nav: vi.fn(), openModal }).find((i) => i.id === "remove")!.onSelect!();
    expect(openModal).toHaveBeenCalledWith("confirm", expect.objectContaining({ danger: true }));
    expect(textOf(openModal.mock.calls[0]![1].title)).toBe("Remove Bob?");
  });

  it("asks the owner before handing the group on, naming who gets it, and makes them the owner", async () => {
    useChannels.setState({ byId: { g: { ...group, owner_id: "me" } } });
    signIn();
    const openModal = vi.fn();
    const items = groupMemberMenu.items({ channelId: "g", userId: "bob", nav: vi.fn(), openModal });
    const owner = items.find((i) => i.id === "owner")!;
    expect(owner.label).toBe("Transfer Ownership");
    expect(owner.danger).toBeFalsy();
    owner.onSelect!();
    const [kind, props] = openModal.mock.calls[0]! as [string, { title: ReactNode; body: ReactNode; confirmLabel: string; onConfirm: () => Promise<unknown> }];
    expect(kind).toBe("confirm");
    expect(textOf(props.title)).toBe("Transfer Ownership of “Crew”?");
    expect(textOf(props.body)).toBe("Bob becomes the group's owner, able to remove people and delete the group, and you become a member like everyone else.");
    expect(props.confirmLabel).toBe("Transfer Ownership");
    await props.onConfirm();
    expect(api.patch).toHaveBeenCalledWith("g", { owner_id: "bob" });
  });

  it("puts Remove from Group last among the owner's actions, as a danger, and removes once confirmed", async () => {
    useChannels.setState({ byId: { g: { ...group, owner_id: "me" } } });
    signIn();
    const openModal = vi.fn();
    const items = groupMemberMenu.items({ channelId: "g", userId: "bob", nav: vi.fn(), openModal });
    const remove = items.find((i) => i.id === "remove")!;
    expect(items[items.indexOf(remove) - 1]?.id).toBe("owner");
    expect([remove.id, remove.label, remove.danger]).toEqual(["remove", "Remove from Group", true]);
    remove.onSelect!();
    const props = openModal.mock.calls[0]![1] as { title: ReactNode; body: ReactNode; danger: boolean; onConfirm: () => Promise<unknown> };
    expect(textOf(props.title)).toBe("Remove Bob?");
    expect(textOf(props.body)).toBe("Bob will be removed from “Crew” and stop getting its messages. They'll need a new invite to come back.");
    await props.onConfirm();
    expect(api.removeRecipient).toHaveBeenCalledWith("g", "bob");
  });
});

describe("groupMemberMenu in a one-to-one conversation", () => {
  const dm = { id: "d", type: "dm", parent_id: null, recipient_ids: ["me", "bob"] } as unknown as Channel;
  const dmIds = (userId: string) => groupMemberMenu.items({ channelId: "d", userId, nav: vi.fn(), openModal: vi.fn() }).map((i) => i.id);

  beforeEach(() => {
    useChannels.setState({ byId: { d: dm } });
    useRelationships.setState({ byUser: {} });
    useUsers.setState({ byId: { bob: { id: "bob", username: "bob", display_name: "Bob", flags: 0 } as PublicUser } });
  });

  it("offers the person's choices and none of a group's", () => {
    signIn(ADMIN);
    expect(dmIds("bob")).toEqual(["profile", "mention", "message", "add-friend", "block", "copy-profile-link"]);
    // there is no group to leave, hand on or remove anyone from
    expect(dmIds("me")).toEqual(["profile", "mention"]);
  });
});
