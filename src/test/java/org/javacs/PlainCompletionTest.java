package org.javacs;

import static org.junit.Assert.*;

import com.google.gson.JsonElement;
import java.nio.file.Files;
import org.javacs.lsp.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PlainCompletionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void completionRespectsSnippetCapability() throws Exception {
        for (var snippets : new Boolean[] {null, false, true}) {
            var root = temporary.newFolder().toPath().toAbsolutePath();
            var text = "class Main { Object value = \"\".sub; }";
            var file = Files.writeString(root.resolve("Main.java"), text);
            FileStore.reset();
            var server = new JavaLanguageServer(new LanguageClient() {
                public void publishDiagnostics(PublishDiagnosticsParams params) {}
                public void showMessage(ShowMessageParams params) {}
                public void registerCapability(String method, JsonElement options) {}
                public void customNotification(String method, JsonElement params) {}
            });
            var json = snippets == null ? "{}" :
                    "{\"capabilities\":{\"textDocument\":{\"completion\":{\"completionItem\":{\"snippetSupport\":"
                            + snippets + "}}}}}";
            var init = JsonHelper.GSON.fromJson(json, InitializeParams.class);
            init.rootUri = root.toUri();
            server.initialize(init);
            server.initialized();
            var open = new DidOpenTextDocumentParams();
            open.textDocument.uri = file.toUri();
            open.textDocument.languageId = "java";
            open.textDocument.version = 1;
            open.textDocument.text = text;
            server.didOpenTextDocument(open);
            var params = new TextDocumentPositionParams(new TextDocumentIdentifier(file.toUri()),
                    new Position(0, text.indexOf("sub") + 3));
            var items = server.completion(params).orElseThrow().items;
            assertTrue(items.stream().anyMatch(item -> item.label.equals("substring")));
            for (var item : items) {
                if (Boolean.TRUE.equals(snippets)) {
                    if (item.label.equals("substring")) assertEquals(Integer.valueOf(2), item.insertTextFormat);
                } else {
                    assertNotEquals(Integer.valueOf(2), item.insertTextFormat);
                }
            }
        }
    }
}
