package org.javacs;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
