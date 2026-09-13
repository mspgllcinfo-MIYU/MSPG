package com.mspg.poicat.room

import android.app.Activity
import com.mspg.poicat.data.CatEventRepository
import com.mspg.poicat.data.FileRepository
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.drive.FileDriveSync
import com.mspg.poicat.drive.PhotoDriveSync

/**
 * ルーム参加時の「既存データのバックフィル」— 参加前からローカルにあった、まだ誰とも
 * 共有していないデータ(CatEventのroomEventId、写真/ファイルのdriveFileIdがまだ無い行)
 * だけを、既存の[RoomEventSync]/[PhotoDriveSync]/[FileDriveSync]の通常の同期経路へ
 * そのまま渡して共有する。新しいFirestore/Drive呼び出しロジックはここには無い。
 *
 * 【設計方針(ユーザー承認済み)】
 * - どちらの端末が「共有元」かを決め打ちしない。参加した端末が、自分のまだ未共有の
 *   行"だけ"を対称に送る処理にする。相手が空ならそれだけ何も送らないというだけの話
 *   で、「どちらかの端末が主」という特別扱いのコードは書かない — 将来どちらの端末
 *   が入れ替わっても同じロジックで安全に動く。
 * - 内容が同じに見えても、別々に作成されたローカル行を「同じもの」とみなして統合
 *   (重複排除)しない。誤って統合して消える方が、別データとして残ることより悪い
 *   という判断による、明示的な設計選択。
 * - グローバルな「バックフィル完了」フラグは持たない。行ごとの既存の状態
 *   (roomEventId/driveFileIdがまだnullかどうか)そのものを進捗として使う — 通信が
 *   途中で切れても、次にこの関数が呼ばれたときに「まだnullの行」だけが自然に
 *   再試行される。
 * - バックフィルはローカル→リモートへの追加送信のみ。リモート側の状態を理由に
 *   ローカルの行を削除・上書きする処理は一切含まない(既存のPhotoRepository/
 *   FileRepository/CatEventRepositoryのdeleteは呼ばない)。
 *
 * 呼び出しタイミング: (1)ルーム参加直後([RoomShareScreen])。(2)アプリ起動のたびに
 * ルーム参加済みなら([MainActivity]、[RoomEventSync.startListening]と同じ場所) —
 * (2)は(1)が通信失敗で終わった場合の自然な再試行機会になる。ルーム未参加なら即noop。
 */
object RoomBackfill {
    suspend fun pushUnsyncedToRoom(activity: Activity) {
        if (RoomStore(activity).roomId == null) return

        // 3種類とも互いに独立 — どれか1つが失敗しても他の2つには影響しない。
        runCatching { CatEventRepository(activity.applicationContext).pushUnsyncedToRoom() }
        runCatching { pushUnsyncedPhotos(activity) }
        runCatching { pushUnsyncedFiles(activity) }
    }

    private suspend fun pushUnsyncedPhotos(activity: Activity) {
        val repository = PhotoRepository(activity.applicationContext)
        repository.all().filter { it.driveFileId == null }.forEach { photo ->
            PhotoDriveSync.syncNewPhoto(activity, repository, photo)
        }
    }

    private suspend fun pushUnsyncedFiles(activity: Activity) {
        val repository = FileRepository(activity.applicationContext)
        repository.all().filter { it.driveFileId == null }.forEach { file ->
            FileDriveSync.syncNewFile(activity, repository, file)
        }
    }
}
