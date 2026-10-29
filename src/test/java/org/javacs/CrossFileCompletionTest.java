package org.javacs;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.net.URI;
import com.google.gson.JsonElement;
import org.javacs.lsp.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CrossFileCompletionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final String MAIN =
            "package demo; class Main { Object value = Helper.; }";
    private static final String HELPER =
            "package demo;\npublic class Helper { public static String greeting() { return \"hi\"; } }";

    private void open(JavaLanguageServer server, Path file, String text) {
        var params = new DidOpenTextDocumentParams();
        params.textDocument.uri = file.toUri();
        params.textDocument.languageId = "java";
        params.textDocument.version = 1;
        params.textDocument.text = text;
        server.didOpenTextDocument(params);
    }

    private List<String> complete(JavaLanguageServer server, Path file) {
        var params = new TextDocumentPositionParams(
                new TextDocumentIdentifier(file.toUri()),
                new Position(0, MAIN.indexOf("Helper.") + "Helper.".length()));
        return server.completion(params).orElseThrow().items.stream()
                .map(CompletionsBase::itemFilterText).toList();
    }

    private void change(JavaLanguageServer server, Path file, int version, String text) {
        var params = new DidChangeTextDocumentParams();
        params.textDocument.uri = file.toUri();
        params.textDocument.version = version;
        var edit = new TextDocumentContentChangeEvent();
        edit.text = text;
        params.contentChanges = List.of(edit);
        server.didChangeTextDocument(params);
    }

    @Test
    public void recoveredPackageOverridesDiskPackageWithoutSaving() throws Exception {
        var root = temporary.getRoot().toPath().toAbsolutePath();
        var source = Files.createDirectories(root.resolve("src/demo"));
        var main = Files.writeString(source.resolve("Main.java"), MAIN);
        var disk = HELPER.replace("package demo", "package old");
        var helper = Files.writeString(source.resolve("Helper.java"), disk);
        var server = LanguageServerFixture.getJavaLanguageServer(root, diagnostic -> {});
        open(server, main, MAIN);
        open(server, helper, HELPER);
        var suggestions = complete(server, main);
        assertTrue(suggestions.toString(), suggestions.contains("greeting"));
        var close = new DidCloseTextDocumentParams();
        close.textDocument = new TextDocumentIdentifier(helper.toUri());
        server.didCloseTextDocument(close);
        assertFalse(complete(server, main).contains("greeting"));
        assertEquals(disk, Files.readString(helper));
    }

    @Test
    public void packageTypedIntoEmptyFileUpdatesSiblingLookupAndDiscardRestoresDisk() throws Exception {
        var root = temporary.getRoot().toPath().toAbsolutePath();
        var source = Files.createDirectories(root.resolve("src/demo"));
        var main = Files.writeString(source.resolve("Main.java"), MAIN);
        var helper = Files.writeString(source.resolve("Helper.java"), "");
        var server = LanguageServerFixture.getJavaLanguageServer(root, diagnostic -> {});
        open(server, main, MAIN);
        open(server, helper, "");
        assertFalse(complete(server, main).contains("greeting"));
        change(server, helper, 2, HELPER);
        var suggestions = complete(server, main);
        assertTrue(suggestions.toString(), suggestions.contains("greeting"));
        change(server, helper, 3, HELPER.replace("package demo", "package other"));
        assertFalse(complete(server, main).contains("greeting"));
        change(server, helper, 4, HELPER);
        assertTrue(complete(server, main).contains("greeting"));
        var close = new DidCloseTextDocumentParams();
        close.textDocument = new TextDocumentIdentifier(helper.toUri());
        server.didCloseTextDocument(close);
        assertFalse(complete(server, main).contains("greeting"));
        assertEquals("", Files.readString(helper));
    }

    @Test
    public void dependencyOnlyEditRefreshesAndClearsDiagnosticsInUntouchedMain() throws Exception {
        var root = temporary.getRoot().toPath().toAbsolutePath();
        var source = Files.createDirectories(root.resolve("src/demo"));
        var text = "package demo; class Main { String value = Helper.greeting(); }";
        var main = Files.writeString(source.resolve("Main.java"), text);
        var helper = Files.writeString(source.resolve("Helper.java"), HELPER);
        var diagnostics = new HashMap<URI, List<Diagnostic>>();
        var server = LanguageServerFixture.getJavaLanguageServer(root, new LanguageClient() {
            public void publishDiagnostics(PublishDiagnosticsParams params) {
                diagnostics.put(params.uri, params.diagnostics);
            }
            public void showMessage(ShowMessageParams params) {}
            public void registerCapability(String method, JsonElement options) {}
            public void customNotification(String method, JsonElement params) {}
        });
        open(server, main, text);
        open(server, helper, HELPER);
        server.doAsyncWork();
        change(server, helper, 2, HELPER.replace("greeting", "farewell"));
        server.doAsyncWork();
        assertTrue(diagnostics.toString(), diagnostics.getOrDefault(main.toUri(), List.of()).stream()
                .anyMatch(d -> d.message.contains("cannot find symbol")));
        change(server, helper, 3, HELPER);
        server.doAsyncWork();
        assertTrue(diagnostics.get(main.toUri()).toString(), diagnostics.get(main.toUri()).isEmpty());
        change(server, helper, 4, HELPER.replace("greeting", "farewell"));
        server.doAsyncWork();
        assertFalse(diagnostics.get(main.toUri()).isEmpty());
        var close = new DidCloseTextDocumentParams();
        close.textDocument = new TextDocumentIdentifier(helper.toUri());
        server.didCloseTextDocument(close);
        server.doAsyncWork();
        assertTrue(diagnostics.get(main.toUri()).toString(), diagnostics.get(main.toUri()).isEmpty());
        assertEquals(text, Files.readString(main));
        assertEquals(HELPER, Files.readString(helper));
    }

    @Test
    public void unsavedDependencyEdit() throws Exception {
        var root = temporary.getRoot().toPath().toAbsolutePath();
        var source = Files.createDirectories(root.resolve("src/demo"));
        var main = Files.writeString(source.resolve("Main.java"), MAIN);
        var helper = Files.writeString(source.resolve("Helper.java"), HELPER);
        var server = LanguageServerFixture.getJavaLanguageServer(root, diagnostic -> {});
        open(server, main, MAIN);
        open(server, helper, HELPER);
        assertTrue(complete(server, main).contains("greeting"));

        var change = new DidChangeTextDocumentParams();
        change.textDocument.uri = helper.toUri();
        change.textDocument.version = 2;
        var text = new TextDocumentContentChangeEvent();
        text.text = HELPER.replace("greeting", "farewell");
        change.contentChanges = List.of(text);
        server.didChangeTextDocument(change);
        var suggestions = complete(server, main);
        assertTrue(suggestions.toString(), suggestions.contains("farewell"));
        assertFalse(suggestions.toString(), suggestions.contains("greeting"));
        assertEquals(HELPER, Files.readString(helper));

        var close = new DidCloseTextDocumentParams();
        close.textDocument = new TextDocumentIdentifier(helper.toUri());
        server.didCloseTextDocument(close);
        suggestions = complete(server, main);
        assertTrue(suggestions.toString(), suggestions.contains("greeting"));
        assertFalse(suggestions.toString(), suggestions.contains("farewell"));
    }

    @Test
    public void compactPackageDeclaration() throws Exception {
        var root = temporary.getRoot().toPath().toAbsolutePath();
        var source = Files.createDirectories(root.resolve("src/demo"));
        var main = Files.writeString(source.resolve("Main.java"), MAIN);
        Files.writeString(source.resolve("Helper.java"), HELPER.replace(";\n", "; "));
        var server = LanguageServerFixture.getJavaLanguageServer(root, diagnostic -> {});
        open(server, main, MAIN);
        var suggestions = complete(server, main);
        assertTrue(suggestions.toString(), suggestions.contains("greeting"));
    }

    @Test
    public void openingNewSiblingDoesNotRequireFileWatcherSupport() throws Exception {
        var root = temporary.getRoot().toPath().toAbsolutePath();
        var source = Files.createDirectories(root.resolve("src/demo"));
        var main = Files.writeString(source.resolve("Main.java"), MAIN);
        var server = LanguageServerFixture.getJavaLanguageServer(root, diagnostic -> {});
        open(server, main, MAIN);
        assertFalse(complete(server, main).contains("greeting"));
        var helper = Files.writeString(source.resolve("Helper.java"), HELPER);
        open(server, helper, HELPER);
        var suggestions = complete(server, main);
        assertTrue(suggestions.toString(), suggestions.contains("greeting"));
    }

    @Test
    public void unsavedCompletionAfterCompactIndexing() throws Exception {
        var root = temporary.getRoot().toPath().toAbsolutePath();
        var source = Files.createDirectories(root.resolve("src/demo"));
        var helper = Files.writeString(source.resolve("Helper.java"), HELPER.replace(";\n", "; "));
        var server = LanguageServerFixture.getJavaLanguageServer(root, diagnostic -> {});
        var text = "package demo; class Helper { Object value = \"\".sub; }";
        open(server, helper, text);
        var params = new TextDocumentPositionParams(
                new TextDocumentIdentifier(helper.toUri()), new Position(0, text.indexOf("sub") + 3));
        var suggestions = server.completion(params).orElseThrow().items.stream()
                .map(CompletionsBase::itemFilterText).toList();
        assertTrue(suggestions.toString(), suggestions.contains("substring"));
    }
}
