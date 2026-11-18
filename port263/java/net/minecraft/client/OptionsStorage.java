// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client;

import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

public final class OptionsStorage {
   private OptionsStorage() {
   }

   @Nullable
   public static String read(final File file) throws IOException {
      return file.exists() ? Files.asCharSource(file, StandardCharsets.UTF_8).read() : null;
   }

   public static void write(final File file, final String contents) throws IOException {
      Files.asCharSink(file, StandardCharsets.UTF_8).write(contents);
   }

   @Nullable
   public static byte[] readBytes(final File file) throws IOException {
      return file.exists() ? Files.asByteSource(file).read() : null;
   }

   public static void writeBytes(final File file, final byte[] contents) throws IOException {
      Files.asByteSink(file).write(contents);
   }
}
