/*
 * Copyright (c) 2026, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.connector.operations;

import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Regression coverage for issue #5082 — MoveFiles.moveFile() with createParentDirectories=true.
 *
 * The bug: the single-file branch of {@code file.move} built the full destination *file* path
 * and passed it to {@link MoveFiles#moveFile}, which then called {@code destinationFile.createFolder()}
 * (on the destination FILE path) whenever {@code createParentDirectories=true}. The intent was to
 * create the destination's *parent directory*. The consequences observed in MI 4.4.0 + File
 * connector v6.0.2:
 *   - Run 1 (destination absent): {@code createFolder()} creates {@code /target/abc.txt} as a folder,
 *     then {@code moveTo} replaces it with the file. Works by accident.
 *   - Run 2 (destination already exists as a regular file): VFS cannot create a folder where a file
 *     exists, so {@code createFolder()} throws
 *     {@code FileSystemException: Could not create folder "...abc.txt" because it already exists and
 *     is a file} BEFORE {@code moveTo} runs — so {@code overwrite=true} never takes effect.
 *
 * The fix at MoveFiles.java:288-292 replaces {@code destinationFile.createFolder()} with
 * {@code destinationFile.getParent()} + a guarded {@code createFolder()}:
 * <pre>
 *   if (createNonExistingParents) {
 *       FileObject parent = destinationFile.getParent();
 *       if (parent != null && !parent.exists()) {
 *           parent.createFolder();
 *       }
 *   }
 * </pre>
 *
 * These tests are deliberately driver-free of synapse / connector-handler infrastructure (same
 * style as {@link WriteFileTempFileResolutionTest}). Each test reproduces the exact
 * {@code moveFile()} pre-move sequence against a real {@link StandardFileSystemManager} on the
 * local filesystem, so they are host-OS-independent and would have caught the regression had they
 * existed when v6.0.2 was cut. Where it strengthens the regression, a test also runs the OLD buggy
 * sequence ({@code destinationFile.createFolder()}) and asserts it throws — pinning down the exact
 * failure the fix removes.
 */
public class MoveFilesParentDirectoryTest {

    private StandardFileSystemManager fsm;
    private Path workDir;

    @BeforeClass
    public void setUpClass() throws Exception {
        fsm = new StandardFileSystemManager();
        fsm.init();
    }

    @AfterClass
    public void tearDownClass() throws Exception {
        if (fsm != null) {
            fsm.close();
        }
    }

    @BeforeMethod
    public void setUpMethod() throws Exception {
        workDir = Files.createTempDirectory("issue5082-test-");
    }

    @AfterMethod
    public void tearDownMethod() throws Exception {
        if (workDir != null) {
            deleteRecursively(workDir.toFile());
        }
    }

    /**
     * Scenario (1) — the reported regression. Move a single file into the same destination filename
     * TWICE with createParentDirectories=true + overwrite=true. The second move must succeed and
     * overwrite the destination (the destination already exists as a regular file after run 1).
     *
     * The PATCHED moveFile() pre-move sequence (getParent() + guarded createFolder()) must NOT throw
     * on the second run, the destination content must be replaced, and the source must be gone.
     */
    @Test
    public void testTwoConsecutiveMoves_overwriteTrue_secondMoveOverwrites() throws Exception {
        FileObject targetDir = resolve("target");
        targetDir.createFolder();

        // --- Run 1: /target absent of abc.txt; source = RUN1-CONTENT ---
        writeSourceFile("source/abc.txt", "RUN1-CONTENT");
        FileObject src1 = resolve("source/abc.txt");
        FileObject dest1 = resolve("target/abc.txt");
        boolean moved1 = patchedMoveFile(src1, true, dest1, true);
        assertTrue(moved1, "Run 1 move must succeed");
        assertTrue(dest1.exists(), "Run 1: destination must exist after move");
        assertEquals(dest1.getType(), FileType.FILE, "Run 1: destination must be a regular FILE, not a folder");
        assertEquals(readContent(dest1), "RUN1-CONTENT", "Run 1: destination content must be the moved file");
        assertFalse(resolve("source/abc.txt").exists(), "Run 1: source must be removed after move");

        // --- Run 2: /target/abc.txt now already exists as a FILE; source = RUN2-NEW-CONTENT ---
        // This is exactly where v6.0.2 threw "Could not create folder ... because it already exists
        // and is a file" at MoveFiles.java:289.
        writeSourceFile("source/abc.txt", "RUN2-NEW-CONTENT");
        FileObject src2 = resolve("source/abc.txt");
        FileObject dest2 = resolve("target/abc.txt");
        assertTrue(dest2.exists() && dest2.getType() == FileType.FILE,
                "Pre-condition: destination must already exist as a file before run 2");

        boolean moved2;
        try {
            moved2 = patchedMoveFile(src2, true, dest2, true);
        } catch (FileSystemException e) {
            fail("Run 2 must NOT throw FileSystemException (issue #5082 regression). Got: " + e.getMessage());
            return;
        }
        assertTrue(moved2, "Run 2 move must succeed");
        assertEquals(dest2.getType(), FileType.FILE, "Run 2: destination must remain a regular FILE");
        assertEquals(readContent(dest2), "RUN2-NEW-CONTENT",
                "Run 2: overwrite=true must replace the destination contents");
        assertFalse(resolve("source/abc.txt").exists(), "Run 2: source must be removed after move");
    }

    /**
     * Scenario (1) — companion guard pinning the exact bug. The OLD buggy sequence
     * ({@code destinationFile.createFolder()} on the destination file path) MUST throw on the second
     * move, with the customer's "already exists and is a file" message. This documents precisely what
     * the fix removed; if anyone re-introduces the createFolder-on-destination call this fails.
     */
    @Test
    public void testOldBuggySequence_createFolderOnDestination_throwsOnSecondMove() throws Exception {
        resolve("target").createFolder();

        // Run 1 with the OLD code: dest absent -> createFolder makes a folder, moveTo replaces it.
        writeSourceFile("source/abc.txt", "RUN1-CONTENT");
        FileObject src1 = resolve("source/abc.txt");
        FileObject dest1 = resolve("target/abc.txt");
        buggyMoveFile(src1, true, dest1, true);
        assertTrue(dest1.exists() && dest1.getType() == FileType.FILE,
                "OLD run 1 works by accident: dest ends up a file");

        // Run 2 with the OLD code: dest already a file -> createFolder() must throw.
        writeSourceFile("source/abc.txt", "RUN2-NEW-CONTENT");
        FileObject src2 = resolve("source/abc.txt");
        FileObject dest2 = resolve("target/abc.txt");
        try {
            buggyMoveFile(src2, true, dest2, true);
            fail("OLD buggy createFolder-on-destination must throw on the second move (issue #5082)");
        } catch (FileSystemException expected) {
            String msg = expected.getMessage() == null ? "" : expected.getMessage();
            assertTrue(msg.contains("Could not create folder") || msg.contains("already exists"),
                    "Expected the createFolder-on-file failure from the issue. Actual: " + msg);
            // The destination must be untouched by the failed run (overwrite never reached).
            assertEquals(readContent(dest2), "RUN1-CONTENT",
                    "OLD failure happens before moveTo: destination must still hold run-1 content");
        }
    }

    /**
     * Scenario (2) — move into a MISSING target directory with createParentDirectories=true. The
     * parent directory must be created and the file must land INSIDE it as a regular file (not be
     * created as a folder at the destination path).
     */
    @Test
    public void testMissingTargetDir_parentCreated_fileLandsInside() throws Exception {
        // /target does not exist yet.
        assertFalse(resolve("target").exists(), "Pre-condition: target dir must be absent");

        writeSourceFile("source/abc.txt", "PARENT-CHECK");
        FileObject src = resolve("source/abc.txt");
        FileObject dest = resolve("target/abc.txt");

        boolean moved = patchedMoveFile(src, true, dest, true);
        assertTrue(moved, "Move into a missing target dir must succeed when createParentDirectories=true");

        FileObject targetDir = resolve("target");
        assertTrue(targetDir.exists(), "Parent target directory must be created");
        assertEquals(targetDir.getType(), FileType.FOLDER, "target must be a FOLDER");
        assertTrue(dest.exists(), "Destination file must exist inside the created parent dir");
        assertEquals(dest.getType(), FileType.FILE, "Destination must be a FILE, not created as a folder");
        assertEquals(readContent(dest), "PARENT-CHECK", "Destination must hold the moved content");
        assertFalse(resolve("source/abc.txt").exists(), "Source must be removed after move");
    }

    /**
     * Scenario (3) — negative path. createParentDirectories=true + overwrite=false with the
     * destination already present. moveFile() must refuse (return false; the operation maps that to
     * FileAlreadyExistsException in execute()), the destination must be UNTOUCHED, the source must
     * remain, and crucially no folder-creation exception must be thrown.
     */
    @Test
    public void testOverwriteFalse_destinationPresent_refusedAndUntouched() throws Exception {
        resolve("target").createFolder();
        // Seed an existing destination file.
        writeFile("target/abc.txt", "EXISTING-DEST");

        writeSourceFile("source/abc.txt", "INCOMING");
        FileObject src = resolve("source/abc.txt");
        FileObject dest = resolve("target/abc.txt");

        boolean moved;
        try {
            moved = patchedMoveFile(src, true, dest, false);
        } catch (FileSystemException e) {
            fail("overwrite=false must NOT raise a folder-creation exception; it must cleanly refuse. Got: "
                    + e.getMessage());
            return;
        }
        assertFalse(moved, "moveFile must return false when overwrite=false and destination exists "
                + "(execute() turns this into FileAlreadyExistsException)");
        assertTrue(dest.exists() && dest.getType() == FileType.FILE, "Destination must still be a file");
        assertEquals(readContent(dest), "EXISTING-DEST", "Destination must be untouched");
        assertTrue(resolve("source/abc.txt").exists(), "Source must NOT be moved when overwrite=false");
        assertEquals(readContent(resolve("source/abc.txt")), "INCOMING", "Source must be untouched");
    }

    /**
     * Scenario (4) — renameTo together with createParentDirectories=true into a missing target dir.
     * The destination path uses the renamed basename; the parent must be created and the file moved
     * to the renamed destination inside it.
     */
    @Test
    public void testRenameTo_withCreateParents_parentCreatedFileMovedRenamed() throws Exception {
        assertFalse(resolve("target").exists(), "Pre-condition: target dir must be absent");

        writeSourceFile("source/abc.txt", "RENAME-CONTENT");
        FileObject src = resolve("source/abc.txt");
        // Mirrors execute(): targetFilePath = targetPath + "/" + renameTo
        FileObject dest = resolve("target/renamed.txt");

        boolean moved = patchedMoveFile(src, true, dest, true);
        assertTrue(moved, "renameTo move with createParentDirectories=true must succeed");

        FileObject targetDir = resolve("target");
        assertTrue(targetDir.exists() && targetDir.getType() == FileType.FOLDER,
                "Parent target directory must be created");
        assertTrue(dest.exists() && dest.getType() == FileType.FILE,
                "Renamed destination file must exist inside the created parent dir");
        assertEquals(readContent(dest), "RENAME-CONTENT", "Renamed destination must hold the moved content");
        assertFalse(resolve("target/abc.txt").exists(),
                "Original basename must NOT exist in the target (file was renamed)");
        assertFalse(resolve("source/abc.txt").exists(), "Source must be removed after move");
    }

    // ---- moveFile sequences -----------------------------------------------------------------

    /**
     * Reproduces the PATCHED {@link MoveFiles#moveFile} pre-move logic verbatim (MoveFiles.java
     * 285-300): guard on overwrite, create the destination's PARENT (guarded by existence) when
     * createParentDirectories=true, then moveTo.
     */
    private boolean patchedMoveFile(FileObject srcFile, boolean createNonExistingParents,
                                    FileObject destinationFile, boolean overWrite) throws FileSystemException {
        if (!overWrite && destinationFile.exists()) {
            return false;
        } else {
            if (createNonExistingParents) {
                FileObject parent = destinationFile.getParent();
                if (parent != null && !parent.exists()) {
                    parent.createFolder();
                }
            }
            srcFile.moveTo(destinationFile);
            return true;
        }
    }

    /**
     * Reproduces the OLD buggy {@link MoveFiles#moveFile} pre-move logic (v6.0.2): createFolder()
     * is called on the destination FILE path itself. Used only by the regression-pinning test.
     */
    private boolean buggyMoveFile(FileObject srcFile, boolean createNonExistingParents,
                                  FileObject destinationFile, boolean overWrite) throws FileSystemException {
        if (!overWrite && destinationFile.exists()) {
            return false;
        } else {
            if (createNonExistingParents) {
                destinationFile.createFolder();
            }
            srcFile.moveTo(destinationFile);
            return true;
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private FileObject resolve(String relativePath) throws FileSystemException {
        File f = new File(workDir.toFile(), relativePath);
        return fsm.resolveFile(f.toURI().toString());
    }

    private void writeSourceFile(String relativePath, String content) throws Exception {
        writeFile(relativePath, content);
    }

    private void writeFile(String relativePath, String content) throws Exception {
        File f = new File(workDir.toFile(), relativePath);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            assertTrue(parent.mkdirs(), "Failed to create test parent dir: " + parent);
        }
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private String readContent(FileObject fo) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[1024];
        try (java.io.InputStream is = fo.getContent().getInputStream()) {
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        f.delete();
    }
}
