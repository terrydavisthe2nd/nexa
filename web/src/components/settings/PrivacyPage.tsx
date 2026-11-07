import { ApiError } from "@/api/client";
import { describeError } from "@/lib/errors";
import { users as usersApi } from "@/api/users";
import type { MeUser } from "@/api/types";
import { Toggle } from "@/components/common/Toggle";
import { useSettings } from "@/stores/settings";
import { SearchSettings } from "@/components/search/SearchSettings";
import { useAuth } from "@/stores/auth";
import { useUI } from "@/stores/ui";
import s from "./Settings.module.css";

import { t } from "@/i18n";
function dmChoices(): { value: MeUser["dm_privacy"]; label: string; desc: string }[] {
  return [
  { value: "everyone", label: t("settings.privacy.everyone"), desc: t("settings.privacy.anyoneWithAnAccountCanMessage") },
  { value: "friends_servers", label: t("settings.privacy.friendsAndServerMembers"), desc: t("settings.privacy.friendsAndServerMembersDesc") },
  { value: "friends", label: t("settings.privacy.friendsOnly"), desc: t("settings.privacy.onlyYourFriendsCanStartA") },
  { value: "nobody", label: t("settings.privacy.nobody"), desc: t("settings.privacy.nobodyCanStartANewDirect") },
];
}

export default function PrivacyPage() {
  const me = useAuth((a) => a.me);
  const setMe = useAuth((a) => a.setMe);
  const toast = useUI((u) => u.toast);
  const perfReports = useSettings((st) => st.settings.perfReports !== false);
  const patch = useSettings((st) => st.patch);
  if (!me) return null;
  const setDM = async (value: MeUser["dm_privacy"]) => {
    try {
      setMe(await usersApi.patchMe({ dm_privacy: value }));
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("settings.privacy.couldNotSave"), "error");
    }
  };
  // kept on the server, since it is other people's apps that go by it: off,
  // friends see this account as Link Only and send it an invite link instead
  const setFriendsAdd = async (allowed: boolean) => {
    try {
      setMe(await usersApi.patchMe({ group_link_only: !allowed }));
    } catch (err) {
      toast(describeError(err, t("settings.privacy.couldNotSave")), "error");
    }
  };
  return (
    <>
      <h2 className={s.title}>{t("settings.privacy.title")}</h2>
      <h3 className={s.h5} data-setting="dmPrivacy">{t("settings.privacy.directMessages")}</h3>
      {dmChoices().map((o) => (
        <label key={o.value} className={s.radio} data-on={me.dm_privacy === o.value}>
          <input type="radio" name="dm" checked={me.dm_privacy === o.value} onChange={() => void setDM(o.value)} />
          <span className={s.radioText}><span className={s.radioLabel}>{o.label}</span><span className={s.radioDesc}>{o.desc}</span></span>
        </label>
      ))}
      <h3 className={s.h5}>{t("settings.privacy.groupChats")}</h3>
      <Toggle setting="groupAdd" checked={!me.group_link_only} onChange={(v) => void setFriendsAdd(v)} label={t("settings.privacy.letFriendsAddMe")} description={t("settings.privacy.letFriendsAddMeHint")} />
      <h3 className={s.h5} data-setting="encryption">{t("server.encryption.encryption")}</h3>
      <p className={s.hint}>{t("settings.privacy.everyMessageAndFileYouSend")}</p>
      <SearchSettings />
      <h3 className={s.h5}>{t("settings.privacy.performance")}</h3>
      <Toggle setting="perfReports" checked={perfReports} onChange={(v) => patch({ perfReports: v })} label={t("settings.privacy.perfReports")} description={t("settings.privacy.perfReportsHint")} />
    </>
  );
}
