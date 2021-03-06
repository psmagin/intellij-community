// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.rebase;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcs.log.VcsCommitMetadata;
import git4idea.DialogManager;
import git4idea.GitVcs;
import git4idea.commands.GitImplBase;
import git4idea.config.GitConfigUtil;
import git4idea.history.GitLogUtil;
import git4idea.rebase.interactive.GitRewordedCommitMessageProvider;
import git4idea.rebase.interactive.RewordedCommitMessageMapping;
import git4idea.rebase.interactive.dialog.GitInteractiveRebaseDialog;
import git4idea.rebase.interactive.dialog.GitRebaseEntryWithEditedMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static com.intellij.CommonBundle.getCancelButtonText;
import static com.intellij.CommonBundle.getOkButtonText;
import static com.intellij.openapi.ui.Messages.getQuestionIcon;
import static git4idea.DialogManager.showOkCancelDialog;
import static git4idea.rebase.GitRebaseEditorMain.ERROR_EXIT_CODE;

/**
 * The handler for rebase editor request. The handler shows the {@link GitInteractiveRebaseDialog}
 * dialog with the specified file. If user accepts the changes, it saves file and returns 0,
 * otherwise it just returns error code.
 */
public class GitInteractiveRebaseEditorHandler implements GitRebaseEditorHandler {
  private final static Logger LOG = Logger.getInstance(GitInteractiveRebaseEditorHandler.class);
  private final Project myProject;
  private final VirtualFile myRoot;

  /**
   * If interactive rebase editor (with the list of commits) was shown, this is true.
   * In that case, the class expects only unstructured editor to edit the commit message.
   */
  protected boolean myRebaseEditorShown = false;

  private boolean myCommitListCancelled;
  private boolean myUnstructuredEditorCancelled;
  private final @NotNull GitRewordedCommitMessageProvider myRewordedCommitMessageProvider;

  public GitInteractiveRebaseEditorHandler(@NotNull Project project, @NotNull VirtualFile root) {
    myProject = project;
    myRoot = root;
    myRewordedCommitMessageProvider = GitRewordedCommitMessageProvider.getInstance(project);
  }

  @Override
  public int editCommits(@NotNull String path) {
    try {
      if (myRebaseEditorShown) {
        String encoding = GitConfigUtil.getCommitEncoding(myProject, myRoot);
        String originalMessage = FileUtil.loadFile(new File(path), encoding);
        String newMessage = myRewordedCommitMessageProvider.getRewordedCommitMessage(myProject, myRoot, originalMessage);
        if (newMessage == null) {
          myUnstructuredEditorCancelled = !handleUnstructuredEditor(path);
          return myUnstructuredEditorCancelled ? ERROR_EXIT_CODE : 0;
        }
        FileUtil.writeToFile(new File(path), newMessage.getBytes(Charset.forName(encoding)));
        return 0;
      }
      else {
        setRebaseEditorShown();
        boolean success = handleInteractiveEditor(path);
        if (success) {
          return 0;
        }
        else {
          myCommitListCancelled = true;
          return ERROR_EXIT_CODE;
        }
      }
    }
    catch (VcsException e) {
      LOG.error("Failed to load commit details for commits from git rebase file: " + path, e);
      return ERROR_EXIT_CODE;
    }
    catch (Exception e) {
      LOG.error("Failed to edit git rebase file: " + path, e);
      return ERROR_EXIT_CODE;
    }
  }

  protected boolean handleUnstructuredEditor(@NotNull String path) throws IOException {
    return GitImplBase.loadFileAndShowInSimpleEditor(myProject, myRoot, path, "Git Commit Message", "Continue Rebasing");
  }

  protected boolean handleInteractiveEditor(@NotNull String path) throws IOException, VcsException {
    GitInteractiveRebaseFile rebaseFile = new GitInteractiveRebaseFile(myProject, myRoot, path);
    try {
      List<GitRebaseEntry> entries = rebaseFile.load();
      List<? extends GitRebaseEntry> newEntries = collectNewEntries(entries);
      if (newEntries != null) {
        rebaseFile.save(newEntries);
        return true;
      }
      else {
        rebaseFile.cancel();
        return false;
      }
    }
    catch (GitInteractiveRebaseFile.NoopException e) {
      return confirmNoopRebase();
    }
  }

  @Nullable
  protected List<? extends GitRebaseEntry> collectNewEntries(@NotNull List<GitRebaseEntry> entries) throws VcsException {
    Ref<List<? extends GitRebaseEntry>> newText = Ref.create();
    List<GitRebaseEntryWithDetails> entriesWithDetails = loadDetailsForEntries(entries);

    ApplicationManager.getApplication().invokeAndWait(() -> {
      newText.set(showInteractiveRebaseDialog(entriesWithDetails));
    });
    return newText.get();
  }

  @Nullable
  private List<? extends GitRebaseEntry> showInteractiveRebaseDialog(List<GitRebaseEntryWithDetails> entriesWithDetails) {
    GitInteractiveRebaseDialog editor = new GitInteractiveRebaseDialog(myProject, myRoot, entriesWithDetails);
    DialogManager.show(editor);
    if (editor.isOK()) {
      List<GitRebaseEntryWithEditedMessage> newEntries = editor.getEntries();
      processNewEntries(newEntries);
      return ContainerUtil.map(newEntries, entry -> entry.getEntry());
    }
    return null;
  }

  protected void processNewEntries(@NotNull List<GitRebaseEntryWithEditedMessage> newEntries) {
    List<RewordedCommitMessageMapping> messages = new ArrayList<>();
    for (GitRebaseEntryWithEditedMessage newEntryWithMessage : newEntries) {
      GitRebaseEntryWithDetails newEntry = newEntryWithMessage.getEntry();
      if (newEntry.getAction() instanceof GitRebaseEntry.Action.REWORD) {
        messages.add(RewordedCommitMessageMapping.fromMapping(
          newEntry.getCommitDetails().getFullMessage(),
          newEntryWithMessage.getNewMessage()
        ));
      }
    }
    myRewordedCommitMessageProvider.save(myProject, myRoot, messages);
  }

  @NotNull
  private List<GitRebaseEntryWithDetails> loadDetailsForEntries(@NotNull List<GitRebaseEntry> entries) throws VcsException {
    List<? extends VcsCommitMetadata> details = GitLogUtil.collectMetadata(
      myProject,
      GitVcs.getInstance(myProject),
      myRoot,
      ContainerUtil.map(entries, entry -> entry.getCommit())
    );
    List<GitRebaseEntryWithDetails> entriesWithDetails = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      entriesWithDetails.add(new GitRebaseEntryWithDetails(entries.get(i), details.get(i)));
    }
    return entriesWithDetails;
  }

  private boolean confirmNoopRebase() {
    LOG.info("Noop situation while rebasing " + myRoot);
    String message = "There are no commits to rebase because the current branch is directly below the base branch, " +
                     "or they point to the same commit (the 'noop' situation).\n" +
                     "Do you want to continue (this will reset the current branch to the base branch)?";
    Ref<Boolean> result = Ref.create(false);
    ApplicationManager.getApplication().invokeAndWait(() -> result.set(
      Messages.OK == showOkCancelDialog(myProject, message, "Git Rebase", getOkButtonText(), getCancelButtonText(), getQuestionIcon())));
    return result.get();
  }

  /**
   * This method is invoked to indicate that this editor will be invoked in the rebase continuation action.
   */
  public void setRebaseEditorShown() {
    myRebaseEditorShown = true;
  }

  @Override
  public boolean wasCommitListEditorCancelled() {
    return myCommitListCancelled;
  }

  @Override
  public boolean wasUnstructuredEditorCancelled() {
    return myUnstructuredEditorCancelled;
  }
}
