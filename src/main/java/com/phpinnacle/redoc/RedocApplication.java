package com.phpinnacle.redoc;

import com.intellij.json.JsonLanguage;
import com.intellij.json.psi.JsonProperty;
import com.intellij.json.psi.JsonValue;
import com.intellij.json.psi.impl.JsonObjectImpl;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.phpinnacle.redoc.settings.RedocSettings;
import com.phpinnacle.redoc.settings.RedocSettings.ChangeListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.YAMLLanguage;
import org.jetbrains.yaml.psi.YAMLPsiElement;
import org.jetbrains.yaml.psi.YAMLValue;
import org.jetbrains.yaml.psi.impl.YAMLDocumentImpl;
import org.jetbrains.yaml.psi.impl.YAMLKeyValueImpl;

import java.util.*;

class RedocApplication implements Disposable {
    private RedocServer server = RedocServer.getInstance();
    private RedocSettings settings = RedocSettings.getInstance();
    private FileDocumentManager manager = FileDocumentManager.getInstance();
    private Application application = ApplicationManager.getApplication();

    private Map<String, String> openApiFields = new HashMap<>();

    RedocApplication() {
        openApiFields.put("swagger", "2.0");
        openApiFields.put("openapi", "3.0.0");
    }

    boolean isAcceptable(@NotNull Project project, @NotNull VirtualFile file) {
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);

        if (psiFile == null) {
            return false;
        }

        return isAcceptableJSON(psiFile) || isAcceptableYAML(psiFile);
    }

    @NotNull
    FileEditor createEditor(@NotNull VirtualFile file) {
        RedocEditor editor = new RedocEditor(server, settings, file.getPath());
        Document document = Objects.requireNonNull(manager.getDocument(file));

        editor.setup(document);

        server.attach(file.getPath(), document);

        application.getMessageBus()
            .connect(editor)
            .subscribe(ChangeListener.TOPIC, new ChangeListener() {
                @Override
                public void settingsChanged(@NotNull RedocSettings settings) {
                    editor.render();
                }
            });

        return editor;
    }

    private boolean isAcceptableJSON(@NotNull PsiFile psiFile) {
        if (!psiFile.getLanguage().is(JsonLanguage.INSTANCE)) {
            return false;
        }

        PsiElement[] children = psiFile.getChildren();

        for (PsiElement element : children) {
            if (!(element instanceof JsonObjectImpl)) {
                continue;
            }

            for (String key : openApiFields.keySet()) {
                JsonProperty property = ((JsonObjectImpl) element).findProperty(key);
                JsonValue value = property != null ? property.getValue() : null;

                if (value != null) {
                    return matchValue(value, key);
                }
            }
        }

        return false;
    }

    private boolean isAcceptableYAML(@NotNull PsiFile psiFile) {
        if (!psiFile.getLanguage().is(YAMLLanguage.INSTANCE)) {
            return false;
        }

        PsiElement[] children = psiFile.getChildren();

        for (PsiElement element : children) {
            if (!(element instanceof YAMLDocumentImpl)) {
                continue;
            }

            YAMLValue top = ((YAMLDocumentImpl) element).getTopLevelValue();

            if (top == null) {
                return false;
            }

            for (YAMLPsiElement child : top.getYAMLElements()) {
                if (!(child instanceof YAMLKeyValueImpl)) {
                    continue;
                }

                for (String key : openApiFields.keySet()) {
                    if (!key.equals(child.getName())) {
                        continue;
                    }

                    YAMLValue value = ((YAMLKeyValueImpl) child).getValue();

                    if (value != null) {
                        return matchValue(value, key);
                    }
                }
            }
        }

        return false;
    }

    @Override
    public void dispose() {
        Disposer.dispose(this);

        server.dispose();
    }

    private boolean matchValue(PsiElement element, String key)
    {
        String version = openApiFields.get(key);

        return element.textMatches(version) || element.textMatches("\"" + version + "\"");
    }
}
