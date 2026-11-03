import type { DecryptedMessage } from "@/lib/decrypt";
import { Suspense, useCallback } from "react";
import { Modal } from "@/components/common/Modal";
import { Spinner } from "@/components/common/Spinner";
import { lazyPage } from "@/lib/lazyChunk";
import { useUI, type ModalState } from "@/stores/ui";
import { ConfirmModal } from "./ConfirmModal";
import { CreateDMModal } from "./CreateDMModal";
import { CustomStatusModal } from "./CustomStatusModal";
import { QuickSwitcher } from "./QuickSwitcher";
import { ShortcutsHelp } from "./ShortcutsHelp";
import { CreateServerModal } from "./CreateServerModal";
import { EditGroupModal } from "./EditGroupModal";
import { PromptModal, type PromptField } from "./PromptModal";
import { InviteModal } from "./InviteModal";
import { GroupInviteModal } from "./GroupInviteModal";
import { CreateChannelModal } from "./CreateChannelModal";
import { CreateCategoryModal } from "./CreateCategoryModal";
import { ServerNotificationsModal } from "./ServerNotificationsModal";
import { ServerProfileModal } from "./ServerProfileModal";
import { ChannelTopicModal } from "./ChannelTopicModal";
import { CreatePollModal } from "./CreatePollModal";
import { IncomingCallHost } from "@/components/voice/IncomingCall";
import { ShareScreenModal } from "@/components/voice/ShareScreenModal";
import { ScreenPermission } from "@/components/desktop/DisplayPicker";
import { GuestLinkModal } from "@/components/voice/GuestLinkModal";
import type { StickerInfo } from "@/api/types";
import type { AppForm } from "@/api/apps";
import { StickerPackModal } from "./StickerPackModal";
import { BlockDialog } from "@/components/profile/BlockDialog";
import type { ProfileTab } from "@/components/profile/ProfileCard";
import { ProfilePopoutHost } from "@/components/profile/ProfilePopoutHost";
import { FolderSettingsModal } from "@/components/rail/FolderSettingsModal";
import type { BugFrom } from "@/components/bugs/ReportBugModal";
import { InviteDialog } from "@/components/invite/InviteDialog";
import { NoteLinkDialog } from "@/components/notes/NoteLinkDialog";
import { CreateThreadModal } from "@/components/threads/CreateThreadModal";
import { FollowDialog } from "@/components/follows/FollowDialog";

import { t } from "@/i18n";
import { PasteUpdateDialog } from "@/components/desktop/PasteUpdateDialog";
const SettingsModal = lazyPage(() => import("@/components/settings/SettingsModal"));
const ProfileModal = lazyPage(() => import("@/components/profile/ProfileModal"));
const ServerSettingsModal = lazyPage(() => import("@/components/server/ServerSettingsModal"));
const ChannelSettingsModal = lazyPage(() => import("@/components/server/ChannelSettingsModal"));
const MemberActionModal = lazyPage(() => import("@/components/server/MemberActionModal"));
const SubchatModal = lazyPage(() => import("./SubchatModal"));
const ForwardModal = lazyPage(() => import("./ForwardModal").then((m) => ({ default: m.ForwardModal })));
const AddAccountModal = lazyPage(() => import("./AddAccountModal"));
const MentionsModal = lazyPage(() => import("@/components/mentions/MentionsModal"));
const GamePage = lazyPage(() => import("@/components/games/GamePage"));
const VerifyKeyModal = lazyPage(() => import("./VerifyKeyModal"));
const KeyChangeModal = lazyPage(() => import("./KeyChangeModal"));
const ReportContentModal = lazyPage(() => import("@/components/reports/ReportModal"));
const ReportBugModal = lazyPage(() => import("@/components/bugs/ReportBugModal"));
const ShareNoteModal = lazyPage(() => import("@/components/notes/ShareNoteModal").then((m) => ({ default: m.ShareNoteModal })));
const AppFormModal = lazyPage(() => import("@/components/apps/AppFormModal").then((m) => ({ default: m.AppFormModal })));

/** Stands in for a dialog whose code is still on its way. */
function Loading({ onClose }: { onClose: () => void }) {
  return (
    <Modal open bare onClose={onClose} ariaLabel={t("chat.pins.loading")} width={120}>
      <div style={{ display: "flex", justifyContent: "center", padding: 32 }}>
        <Spinner size={28} label={t("chat.pins.loading")} />
      </div>
    </Modal>
  );
}

/** Renders every modal on the ui store's stack; later ones overlay earlier ones. */
export function ModalHost() {
  const modals = useUI((u) => u.modals);
  return (
    <>
      <QuickSwitcher />
      <IncomingCallHost />
      {/* someone's profile popout, open beside a name or a picture (stores/profilePopout) */}
      <ProfilePopoutHost />
      {modals.map((m) => (
        <ModalEntry key={m.id} modal={m} />
      ))}
    </>
  );
}

function ModalEntry({ modal }: { modal: ModalState }) {
  const closeModal = useUI((u) => u.closeModal);
  const close = useCallback(() => closeModal(modal.id), [closeModal, modal.id]);
  return (
    <>
      {modal?.kind === "reportContent" ? <Suspense fallback={<Loading onClose={close} />}><ReportContentModal context={modal.props?.["context"] as import("@/lib/reporting").ReportContext | undefined} onClose={close} /></Suspense> : null}
      {modal?.kind === "createDM" ? <CreateDMModal onClose={close} initial={(modal.props?.["initial"] as string[] | undefined) ?? []} groupId={modal.props?.["groupId"] as string | undefined} /> : null}
      {modal?.kind === "confirm" ? (
        <ConfirmModal
          onClose={() => {
            // told however it closes, after a confirm too: whoever waits on a yes or no takes the first answer
            (modal.props?.["onDismiss"] as (() => void) | undefined)?.();
            close();
          }}
          title={String(modal.props?.["title"] ?? t("modals.modalHost.areYouSure"))}
          body={modal.props?.["body"] as React.ReactNode}
          confirmLabel={modal.props?.["confirmLabel"] as string | undefined}
          cancelLabel={modal.props?.["cancelLabel"] as string | undefined}
          danger={Boolean(modal.props?.["danger"])}
          checkboxLabel={modal.props?.["checkboxLabel"] as string | undefined}
          onConfirm={modal.props?.["onConfirm"] as (checked: boolean) => unknown}
        />
      ) : null}
      {modal?.kind === "customStatus" ? <CustomStatusModal onClose={close} /> : null}
      {modal?.kind === "pasteUpdate" ? <PasteUpdateDialog onClose={close} /> : null}
      {modal?.kind === "shortcuts" ? <ShortcutsHelp onClose={close} /> : null}
      {modal?.kind === "createServer" ? <CreateServerModal onClose={close} /> : null}
      {modal?.kind === "invite" ? <InviteModal onClose={close} serverId={String(modal.props?.["serverId"])} channelId={modal.props?.["channelId"] as string | undefined} /> : null}
      {modal?.kind === "groupInvite" ? <GroupInviteModal onClose={close} channelId={String(modal.props?.["channelId"])} /> : null}
      {modal?.kind === "editGroup" ? <EditGroupModal onClose={close} channelId={String(modal.props?.["channelId"])} /> : null}
      {modal?.kind === "prompt" ? (
        <PromptModal
          onClose={close}
          title={String(modal.props?.["title"] ?? "")}
          fields={(modal.props?.["fields"] as PromptField[] | undefined) ?? []}
          confirmLabel={modal.props?.["confirmLabel"] as string | undefined}
          allowEmpty={Boolean(modal.props?.["allowEmpty"])}
          resolve={modal.props?.["resolve"] as (v: Record<string, string> | null) => void}
        />
      ) : null}
      {modal?.kind === "createChannel" ? <CreateChannelModal onClose={close} serverId={String(modal.props?.["serverId"])} parentId={modal.props?.["parentId"] as string | undefined} /> : null}
      {modal?.kind === "createCategory" ? <CreateCategoryModal onClose={close} serverId={String(modal.props?.["serverId"])} /> : null}
      {modal?.kind === "serverNotifications" ? <ServerNotificationsModal onClose={close} serverId={String(modal.props?.["serverId"])} /> : null}
      {modal?.kind === "shareScreen" ? <ShareScreenModal onClose={close} /> : null}
      {modal?.kind === "guestLink" ? <GuestLinkModal onClose={close} channelId={String(modal.props?.["channelId"])} /> : null}
      {modal?.kind === "channelTopic" ? <ChannelTopicModal onClose={close} channelId={String(modal.props?.["channelId"])} /> : null}
      {modal?.kind === "createPoll" ? <CreatePollModal onClose={close} channelId={String(modal.props?.["channelId"])} /> : null}
      {modal?.kind === "follow" ? <FollowDialog onClose={close} channelId={String(modal.props?.["channelId"])} /> : null}
      {modal?.kind === "createThread" ? <CreateThreadModal onClose={close} channelId={String(modal.props?.["channelId"])} messageId={modal.props?.["messageId"] as string | undefined} /> : null}
      {modal?.kind === "stickerPack" ? <StickerPackModal onClose={close} info={modal.props?.["info"] as StickerInfo} stickerId={String(modal.props?.["stickerId"])} /> : null}
      {modal?.kind === "serverProfile" ? <ServerProfileModal onClose={close} serverId={String(modal.props?.["serverId"])} userId={modal.props?.["userId"] as string | undefined} /> : null}
      {modal?.kind === "block" ? <BlockDialog onClose={close} userId={String(modal.props?.["userId"])} /> : null}
      {modal?.kind === "folderSettings" ? <FolderSettingsModal onClose={close} folderId={modal.props?.["folderId"] as string | undefined} serverIds={modal.props?.["serverIds"] as string[] | undefined} /> : null}
      {modal?.kind === "joinInvite" ? <InviteDialog onClose={close} code={String(modal.props?.["code"])} hash={String(modal.props?.["hash"] ?? "")} /> : null}
      {modal?.kind === "noteLink" ? <NoteLinkDialog onClose={close} token={String(modal.props?.["token"])} hash={String(modal.props?.["hash"] ?? "")} /> : null}
      {/* The first opening of each of these waits for its code to arrive, and
          on a slow phone connection a tap that shows nothing for seconds is a
          tap people repeat: the dialog's place is taken at once instead. */}
      <Suspense fallback={<Loading onClose={close} />}>
        {modal?.kind === "settings" ? <SettingsModal onClose={close} page={(modal.props?.["page"] as string | undefined) ?? "account"} packId={modal.props?.["packId"] as string | undefined} modalId={modal.id} resume={modal.props?.["resume"] as string | undefined} anchor={modal.props?.["anchor"] as string | undefined} serverId={modal.props?.["serverId"] as string | undefined} /> : null}
        {modal?.kind === "profile" ? <ProfileModal onClose={close} userId={modal.props?.["userId"] as string} tab={modal.props?.["tab"] as ProfileTab | undefined} full={!!modal.props?.["full"]} /> : null}
        {modal?.kind === "screenPermission" ? <ScreenPermission stale={Boolean(modal.props?.["stale"])} onClose={close} /> : null}
        {modal?.kind === "addAccount" ? <AddAccountModal onClose={close} /> : null}
        {modal?.kind === "mentions" ? <MentionsModal onClose={close} /> : null}
        {modal?.kind === "game" ? <GamePage onClose={close} gameId={String(modal.props?.["gameId"])} /> : null}
        {modal?.kind === "forward" ? <ForwardModal onClose={close} msg={modal.props?.["msg"] as DecryptedMessage} /> : null}
        {modal?.kind === "subchat" ? <SubchatModal onClose={close} channel={modal.props?.["channel"] as never} parentId={modal.props?.["parentId"] as string | undefined} name={modal.props?.["name"] as string | undefined} /> : null}
        {modal?.kind === "serverSettings" ? <ServerSettingsModal onClose={close} serverId={String(modal.props?.["serverId"])} page={modal.props?.["page"] as string | undefined} /> : null}
        {modal?.kind === "channelSettings" ? <ChannelSettingsModal onClose={close} channelId={String(modal.props?.["channelId"])} page={modal.props?.["page"] as string | undefined} /> : null}
        {modal?.kind === "memberAction" ? <MemberActionModal onClose={close} serverId={String(modal.props?.["serverId"])} userId={String(modal.props?.["userId"])} action={modal.props?.["action"] as "kick" | "ban" | "timeout" | "roles"} /> : null}
        {modal?.kind === "verifyKey" ? <VerifyKeyModal onClose={close} userId={String(modal.props?.["userId"])} /> : null}
        {modal?.kind === "keyChange" ? <KeyChangeModal onClose={close} /> : null}
        {modal?.kind === "reportBug" ? <ReportBugModal onClose={close} from={modal.props?.["from"] as BugFrom | undefined} /> : null}
        {modal?.kind === "shareNote" ? <ShareNoteModal onClose={close} noteId={String(modal.props?.["noteId"])} /> : null}
        {modal?.kind === "appForm" ? <AppFormModal onClose={close} form={modal.props?.["form"] as AppForm} appId={String(modal.props?.["appId"])} channelId={String(modal.props?.["channelId"])} interactionId={String(modal.props?.["interactionId"])} /> : null}
      </Suspense>
    </>
  );
}
