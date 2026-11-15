package app.gallery.backup

import app.gallery.backup.BackupPlanner.DestFile
import app.gallery.backup.BackupPlanner.IndexedFile
import app.gallery.backup.BackupPlanner.LocalFile
import app.gallery.backup.BackupPlanner.Reason
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneOffset

class BackupPlannerTest {
    private val cfg = BackupPlanner.Config("/Photos", perBucketFolder = true, yearMonthFolders = false, zone = ZoneOffset.UTC)
    private fun local(id: Long, name: String, size: Long, t: Long = 1_700_000_000L + id, bucket: String = "Camera") = LocalFile(id, name, size, t, bucket)

    @Test fun `missing files get a target path in the bucket folder`() {
        val plan = BackupPlanner.plan(listOf(local(1, "a.jpg", 100)), emptyList(), emptyList(), emptyMap(), cfg)
        assertThat(plan.present).isEmpty()
        assertThat(plan.missing.single().targetPath).isEqualTo("/Photos/Camera/a.jpg")
        assertThat(plan.missingBytes).isEqualTo(100)
    }

    @Test fun `same name and size in destination counts as present`() {
        val plan = BackupPlanner.plan(listOf(local(1, "a.jpg", 100)), listOf(DestFile("/Photos/Camera/a.jpg", 100, 55)), emptyList(), emptyMap(), cfg)
        assertThat(plan.missing).isEmpty()
        assertThat(plan.present.single().reason).isEqualTo(Reason.SAME_NAME_IN_DEST)
        assertThat(plan.present.single().remoteFileId).isEqualTo(55)
    }

    @Test fun `name clash with different size uploads with a suffix and never overwrites`() {
        val plan = BackupPlanner.plan(
            listOf(local(1, "a.jpg", 100)),
            listOf(DestFile("/Photos/Camera/a.jpg", 999, 55), DestFile("/Photos/Camera/a (1).jpg", 998, 56)),
            emptyList(), emptyMap(), cfg,
        )
        assertThat(plan.missing.single().targetPath).isEqualTo("/Photos/Camera/a (2).jpg")
    }

    @Test fun `earlier suffixed upload with same size is recognised`() {
        val plan = BackupPlanner.plan(
            listOf(local(1, "a.jpg", 100)),
            listOf(DestFile("/Photos/Camera/a.jpg", 999, 55), DestFile("/Photos/Camera/a (1).jpg", 100, 56)),
            emptyList(), emptyMap(), cfg,
        )
        assertThat(plan.missing).isEmpty()
        assertThat(plan.present.single().remoteFileId).isEqualTo(56)
    }

    @Test fun `photo elsewhere on the server is found by capture time and size`() {
        val l = local(1, "IMG_1.jpg", 100, t = 1_700_000_123)
        val plan = BackupPlanner.plan(listOf(l), emptyList(), listOf(IndexedFile(77, 1_700_000_123, 100, "renamed.jpg")), emptyMap(), cfg)
        assertThat(plan.present.single().reason).isEqualTo(Reason.SAME_TIME_AND_SIZE)
    }

    @Test fun `photo elsewhere on the server is found by name and size`() {
        val plan = BackupPlanner.plan(listOf(local(1, "IMG_1.jpg", 100)), emptyList(), listOf(IndexedFile(77, 5, 100, "img_1.JPG")), emptyMap(), cfg)
        assertThat(plan.present.single().reason).isEqualTo(Reason.SAME_NAME_AND_SIZE)
    }

    @Test fun `upload record counts only while the server file still exists`() {
        val l = local(1, "a.jpg", 100)
        val still = BackupPlanner.plan(listOf(l), emptyList(), listOf(IndexedFile(9, 0, 0, "x")), mapOf(1L to 9L), cfg)
        assertThat(still.present.single().reason).isEqualTo(Reason.UPLOAD_RECORD)
        val gone = BackupPlanner.plan(listOf(l), emptyList(), emptyList(), mapOf(1L to 9L), cfg)
        assertThat(gone.missing).hasSize(1)
    }

    @Test fun `two phone files with the same name get different targets`() {
        val plan = BackupPlanner.plan(listOf(local(1, "a.jpg", 100), local(2, "a.jpg", 200)), emptyList(), emptyList(), emptyMap(), cfg)
        assertThat(plan.missing.map { it.targetPath }).containsExactly("/Photos/Camera/a.jpg", "/Photos/Camera/a (1).jpg")
    }

    @Test fun `year and month folders`() {
        val c = cfg.copy(yearMonthFolders = true)
        // 2023-11-14T22:13:20Z
        val path = BackupPlanner.plan(listOf(local(1, "a.jpg", 1, t = 1_700_000_000)), emptyList(), emptyList(), emptyMap(), c).missing.single().targetPath
        assertThat(path).isEqualTo("/Photos/Camera/2023/11/a.jpg")
    }

    @Test fun `flat layout without bucket folders`() {
        val c = cfg.copy(perBucketFolder = false)
        val path = BackupPlanner.plan(listOf(local(1, "a.jpg", 1)), emptyList(), emptyList(), emptyMap(), c).missing.single().targetPath
        assertThat(path).isEqualTo("/Photos/a.jpg")
    }

    @Test fun `freePath keeps extension`() {
        assertThat(BackupPlanner.freePath("/P", "x.tar.gz", setOf("/p/x.tar.gz"))).isEqualTo("/P/x.tar (1).gz")
        assertThat(BackupPlanner.freePath("/P", "noext", setOf("/p/noext"))).isEqualTo("/P/noext (1)")
    }
}
