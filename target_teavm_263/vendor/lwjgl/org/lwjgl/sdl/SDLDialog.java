// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public final class SDLDialog {
    public static final int SDL_FILEDIALOG_OPENFILE = 0;
    public static final int SDL_FILEDIALOG_SAVEFILE = 1;

    private SDLDialog() { }

    public static void SDL_ShowFileDialogWithProperties(int type, SDL_DialogFileCallbackI callback,
            long userdata, int properties) {
        Objects.requireNonNull(callback);
        if (!SDLProperties.exists(properties) || (type != SDL_FILEDIALOG_OPENFILE && type != SDL_FILEDIALOG_SAVEFILE)) {
            fail(callback, userdata, "Invalid or unsupported SDL file dialog");
            return;
        }
        String title = SDLProperties.SDL_GetStringProperty(properties, "SDL.filedialog.title", "Select file");
        String location = SDLProperties.SDL_GetStringProperty(properties, "SDL.filedialog.location", "settings.txt");
        long filters = SDLProperties.SDL_GetPointerProperty(properties, "SDL.filedialog.filters", 0);
        long count = SDLProperties.SDL_GetNumberProperty(properties, "SDL.filedialog.nfilters", 0);
        StringBuilder accept = new StringBuilder();
        if (count < 0 || count > 256 || (count > 0 && filters == 0)) {
            fail(callback, userdata, "Invalid SDL dialog filters");
            return;
        }
        for (int i = 0; i < count; i++) {
            long pattern = MemoryUtil.memGetAddress(filters + (long) i * SDL_DialogFileFilter.SIZEOF + 8);
            if (pattern == 0) continue;
            String value = MemoryUtil.memUTF8(pattern);
            if (value.equals("*")) { accept.setLength(0); break; }
            for (String extension : value.split(";")) {
                if (extension.matches("[a-zA-Z0-9_-]+")) {
                    if (accept.length() > 0) accept.append(',');
                    accept.append('.').append(extension);
                }
            }
        }
        boolean save = type == SDL_FILEDIALOG_SAVEFILE;
        show(save, title, safeName(location), accept.toString(), (name, bytes, error) -> {
            if (error != null) {
                fail(callback, userdata, error);
                return;
            }
            if (name == null) {
                deliver(callback, userdata, null);
                return;
            }
            Path directory = null;
            Path selected = null;
            try {
                try {
                    directory = Files.createTempDirectory("sdl-dialog-");
                    selected = directory.resolve(safeName(name));
                    if (!save) Files.write(selected, bytes.copyToJavaArray());
                } catch (IOException e) {
                    fail(callback, userdata, e.toString());
                    return;
                }
                deliver(callback, userdata, selected.toAbsolutePath().toString());
                if (save) {
                    try {
                        download(selected.getFileName().toString(), Int8Array.copyFromJavaArray(Files.readAllBytes(selected)));
                    } catch (IOException e) {
                        System.err.println("[sdl-web] selected SAVE file was not written before callback return: " + e);
                    }
                }
            } finally {
                try {
                    if (selected != null) Files.deleteIfExists(selected);
                    if (directory != null) Files.deleteIfExists(directory);
                } catch (IOException e) {
                    System.err.println("[sdl-web] temporary file cleanup failed: " + e);
                }
            }
        });
    }

    private static String safeName(String name) {
        int start = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1;
        StringBuilder out = new StringBuilder();
        for (int i = start; i < name.length() && out.length() < 128; i++) {
            char c = name.charAt(i);
            out.append(c < ' ' || c == ':' ? '_' : c);
        }
        String value = out.toString();
        return value.isEmpty() || value.equals(".") || value.equals("..") ? "settings.txt" : value;
    }

    private static void fail(SDL_DialogFileCallbackI callback, long userdata, String message) {
        System.err.println("[sdl-web] " + message);
        callback.invoke(userdata, 0, -1);
    }

    private static void deliver(SDL_DialogFileCallbackI callback, long userdata, String path) {
        ByteBuffer text = path == null ? null : MemoryUtil.memUTF8(path);
        ByteBuffer list = MemoryUtil.memCalloc(2, 8);
        try {
            if (text != null) MemoryUtil.memPutLong(MemoryUtil.memAddress(list), MemoryUtil.memAddress(text));
            callback.invoke(userdata, MemoryUtil.memAddress(list), -1);
        } finally {
            MemoryUtil.memFree(list);
            if (text != null) MemoryUtil.memFree(text);
        }
    }

    @JSFunctor
    private interface Selection extends JSObject {
        void selected(String name, Int8Array bytes, String error);
    }

    @JSBody(params = {"save", "title", "name", "accept", "result"}, script = """
        var input = null, finished = false;
        var finish = function(name, bytes, error) {
            if (finished) return;
            finished = true;
            if (input) { input.onchange = input.oncancel = null; input.remove(); }
            // Return to Iris so its whenComplete handler is attached before SAVE completes.
            setTimeout(function() { result(name, bytes, error); }, 0);
        };
        try {
            if (typeof document === 'undefined') { finish(null, null, 'File dialogs require a browser page'); return; }
            if (save) {
                var chosen = window.prompt(title, name);
                finish(chosen, null, null);
                return;
            }
            if (navigator.userActivation && !navigator.userActivation.isActive) {
                finish(null, null, 'File selection requires a user click'); return;
            }
            input = document.createElement('input');
            input.type = 'file'; input.accept = accept; input.style.display = 'none';
            input.setAttribute('aria-label', title);
            input.oncancel = function() { finish(null, null, null); };
            input.onchange = function() {
                var file = input.files && input.files[0];
                if (!file) { finish(null, null, null); return; }
                if (file.size > 67108864) { finish(null, null, 'Selected file exceeds 64 MiB'); return; }
                var reader = new FileReader();
                reader.onload = function() { finish(file.name, new Int8Array(reader.result), null); };
                reader.onerror = reader.onabort = function() {
                    finish(null, null, reader.error ? String(reader.error) : 'File read aborted');
                };
                reader.readAsArrayBuffer(file);
            };
            document.body.appendChild(input);
            input.click();
        } catch (e) { finish(null, null, String(e)); }
        """)
    private static native void show(boolean save, String title, String name, String accept, Selection result);

    @JSBody(params = {"name", "bytes"}, script = """
        var url = URL.createObjectURL(new Blob([bytes], {type: 'application/octet-stream'}));
        var link = document.createElement('a');
        link.href = url; link.download = name; link.style.display = 'none';
        document.body.appendChild(link); link.click();
        setTimeout(function() { link.remove(); URL.revokeObjectURL(url); }, 1000);
        """)
    private static native void download(String name, Int8Array bytes);
}
