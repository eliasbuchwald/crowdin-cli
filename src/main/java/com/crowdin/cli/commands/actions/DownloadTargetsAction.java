package com.crowdin.cli.commands.actions;

import com.crowdin.cli.BaseCli;
import com.crowdin.cli.client.CrowdinProjectFull;
import com.crowdin.cli.client.ProjectClient;
import com.crowdin.cli.commands.NewAction;
import com.crowdin.cli.commands.Outputter;
import com.crowdin.cli.commands.functionality.FilesInterface;
import com.crowdin.cli.commands.functionality.ProjectFilesUtils;
import com.crowdin.cli.commands.functionality.RequestBuilder;
import com.crowdin.cli.properties.PropertiesWithTargets;
import com.crowdin.cli.properties.TargetBean;
import com.crowdin.cli.utils.PlaceholderUtil;
import com.crowdin.cli.utils.concurrency.ConcurrencyUtil;
import com.crowdin.cli.utils.console.ConsoleSpinner;
import com.crowdin.client.labels.model.Label;
import com.crowdin.client.languages.model.Language;
import com.crowdin.client.sourcefiles.model.Branch;
import com.crowdin.client.translations.model.ExportProjectTranslationRequest;
import lombok.NonNull;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.crowdin.cli.BaseCli.FILE_FORMAT_MAPPER;
import static com.crowdin.cli.BaseCli.RESOURCE_BUNDLE;
import static com.crowdin.cli.utils.console.ExecutionStatus.OK;
import static com.crowdin.cli.utils.console.ExecutionStatus.WARNING;

class DownloadTargetsAction implements NewAction<PropertiesWithTargets, ProjectClient> {

    private List<String> targetNames;
    private FilesInterface files;
    private boolean noProgress;
    private List<String> langIds;
    private boolean isVerbose;
    private Boolean skipTranslatedOnly;
    private Boolean skipUntranslatedFiles;
    private Boolean exportApprovedOnly;
    private boolean plainView;
    private boolean debug;

    public DownloadTargetsAction(
        @NonNull List<String> targetNames, FilesInterface files, boolean noProgress, List<String> langIds, boolean isVerbose,
        Boolean skipTranslatedOnly, Boolean skipUntranslatedFiles, Boolean exportApprovedOnly, boolean plainView, boolean debug
    ) {
        this.targetNames = targetNames;
        this.files = files;
        this.noProgress = noProgress;
        this.langIds = langIds;
        this.isVerbose = isVerbose;
        this.skipTranslatedOnly = skipTranslatedOnly;
        this.skipUntranslatedFiles = skipUntranslatedFiles;
        this.exportApprovedOnly = exportApprovedOnly;
        this.plainView = plainView;
        this.debug = debug;
    }

    @Override
    public void act(Outputter out, PropertiesWithTargets pb, ProjectClient client) {
        CrowdinProjectFull project = ConsoleSpinner
            .execute(out, "message.spinner.fetching_project_info", "error.collect_project_info",
                this.noProgress, this.plainView, client::downloadFullProject);

        PlaceholderUtil placeholderUtil =
            new PlaceholderUtil(
                project.getSupportedLanguages(), project.getProjectLanguages(true), pb.getBasePath());

        Map<String, Long> projectFiles = ProjectFilesUtils.buildFilePaths(project.getDirectories(), project.getBranches(), project.getFileInfos())
            .entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getId()));
        Map<String, Long> projectDirs = ProjectFilesUtils.buildDirectoryPaths(project.getDirectories())
            .entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        Map<String, Long> projectBranches = project.getBranches()
            .values().stream()
            .collect(Collectors.toMap(Branch::getName, Branch::getId));

        List<TargetBean> targetBeans = new ArrayList<>();
        if ((targetNames.size() == 1 && targetNames.get(0).equals(BaseCli.ALL)) || targetNames.isEmpty()) {
            targetBeans.addAll(pb.getTargets());
        } else {
            Map<String, TargetBean> allTargetNames = pb.getTargets().stream()
                .collect(Collectors.toMap(TargetBean::getName, Function.identity()));
            for (String targetName : targetNames) {
                if (allTargetNames.containsKey(targetName)) {
                    targetBeans.add(allTargetNames.get(targetName));
                } else {
                    out.println(WARNING.withIcon(String.format(RESOURCE_BUNDLE.getString("message.no_target_to_exec"), targetName)));
                }
            }
        }

        Map<String, Long> labels = client.listLabels().stream()
            .collect(Collectors.toMap(Label::getTitle, Label::getId));

        Map<String, Language> projectLanguages = project.getProjectLanguages(false)
            .stream()
            .collect(Collectors.toMap(Language::getId, Function.identity()));

        List<String> specifiedLangs;
        if ((langIds.size() == 1 && langIds.get(0).equals(BaseCli.ALL)) || langIds.isEmpty()) {
            specifiedLangs = new ArrayList<>(projectLanguages.keySet());
        } else {
            String unfoundLangs = langIds.stream()
                .filter(lang -> !projectLanguages.containsKey(lang))
                .map(lang -> "'" + lang + "'")
                .collect(Collectors.joining(", "));
            if (!unfoundLangs.isEmpty()) {
                throw new RuntimeException(String.format(RESOURCE_BUNDLE.getString("error.languages_not_exist"), unfoundLangs));
            } else {
                specifiedLangs = langIds;
            }
        }

        for (TargetBean tb : targetBeans) {

            List<Runnable> tasks = tb.getFiles().stream()
                .flatMap(fb -> {
                    List<String> errors = new ArrayList<>();

                    if (!FILE_FORMAT_MAPPER.containsKey(FilenameUtils.getExtension(fb.getTarget()))) {
                        errors.add(String.format(RESOURCE_BUNDLE.getString("error.crowdin_not_support_file_format"), FilenameUtils.getExtension(fb.getTarget())));
                    }
                    String exportFileFormat = FILE_FORMAT_MAPPER.get(FilenameUtils.getExtension(fb.getTarget()));

                    Boolean exportWithMinApprovalsCount = exportApprovedOnly;
                    ExportProjectTranslationRequest templateRequest
                        = RequestBuilder.exportProjectTranslation(
                            exportFileFormat, skipTranslatedOnly, skipUntranslatedFiles, exportWithMinApprovalsCount);
                    if (fb.getSources() != null && !fb.getSources().isEmpty()) {
                        List<Long> sourceIds = new ArrayList<>();
                        for (String source : fb.getSources()) {
                            if (projectFiles.containsKey(source)) {
                                sourceIds.add(projectFiles.get(source));
                            } else {
                                errors.add(String.format(RESOURCE_BUNDLE.getString("error.file_not_exists"), source));
                            }
                        }
                        templateRequest.setFileIds(sourceIds);
                    } else if (fb.getSourceDirs() != null && !fb.getSourceDirs().isEmpty()) {
                        List<Long> dirIds = new ArrayList<>();
                        for (String dir : fb.getSourceDirs()) {
                            if (projectDirs.containsKey(dir)) {
                                dirIds.add(projectDirs.get(dir));
                            } else {
                                errors.add(String.format(RESOURCE_BUNDLE.getString("error.dir_not_exists"), dir));
                            }
                        }
                        templateRequest.setDirectoryIds(dirIds);
                    } else if (fb.getSourceBranches() != null && !fb.getSourceBranches().isEmpty()) {
                        List<Long> branchIds = new ArrayList<>();
                        for (String branch : fb.getSourceDirs()) {
                            if (projectBranches.containsKey(branch)) {
                                branchIds.add(projectBranches.get(branch));
                            } else {
                                errors.add(String.format(RESOURCE_BUNDLE.getString("error.branch_not_exists"), branch));
                            }
                        }
                        templateRequest.setBranchIds(branchIds);
                    } else {
                        throw new RuntimeException("Unexpected error: no source identifiers");
                    }


                    if (fb.getLabels() != null) {
                        List<Long> labelIds = new ArrayList<>();
                        for (String labelTitle : fb.getLabels()) {
                            if (labels.containsKey(labelTitle)) {
                                labelIds.add(labels.get(labelTitle));
                            } else {
                                errors.add(out.format(String.format(RESOURCE_BUNDLE.getString("error.label_not_exists"), labelTitle)));
                            }
                        }
                        templateRequest.setLabelIds(labelIds);
                    }

                    List<Pair<String, ExportProjectTranslationRequest>> builtRequests = new ArrayList<>();
                    for (String langId : specifiedLangs) {
                        ExportProjectTranslationRequest request = RequestBuilder.exportProjectTranslation(templateRequest);
                        request.setTargetLanguageId(langId);
                        String targetFileLang = placeholderUtil.replaceLanguageDependentPlaceholders(fb.getTarget(), projectLanguages.get(langId));
                        builtRequests.add(Pair.of(targetFileLang, request));
                    }

                    if (!errors.isEmpty()) {
                        String listOfErrors = errors.stream()
                            .map(er -> String.format("\t- %s", er))
                            .collect(Collectors.joining("\n"));
                        throw new RuntimeException(String.format(RESOURCE_BUNDLE.getString("error.target_has_list_of_errors") + "\n%s", tb.getName(), listOfErrors));
                    } else {
                        return builtRequests.stream();
                    }
                })
                .map(builtRequest -> (Runnable) () -> {
                    String targetFile = builtRequest.getLeft();
                    ExportProjectTranslationRequest request = builtRequest.getRight();

                    URL downloadUrl = client.exportProjectTranslation(request);
                    try (InputStream data = downloadUrl.openStream()) {
                        files.writeToFile(targetFile, data);
                    } catch (IOException e) {
                        throw new RuntimeException(String.format(RESOURCE_BUNDLE.getString("error.write_file"), targetFile), e);
                    }
                    out.println(OK.withIcon(
                        String.format(RESOURCE_BUNDLE.getString("message.target_success"),
                            tb.getName(), builtRequest.getValue().getTargetLanguageId())));
                })
                .collect(Collectors.toList());

            ConcurrencyUtil.executeAndWait(tasks, debug);
        }

        if (pb.getTargets().isEmpty()) {
            out.println(WARNING.withIcon(RESOURCE_BUNDLE.getString("message.no_targets_to_exec")));
        }
    }

}
