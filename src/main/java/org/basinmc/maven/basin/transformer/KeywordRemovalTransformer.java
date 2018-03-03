/*
 * Copyright 2018 Johannes Donath <johannesd@torchmind.com>
 * and other copyright owners as documented in the project's IP log.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.basinmc.maven.basin.transformer;

import edu.emory.mathcs.backport.java.util.Arrays;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import org.apache.commons.codec.binary.Hex;
import org.basinmc.plunger.bytecode.transformer.BytecodeTransformer;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Provides a bytecode level transformer which will automatically rename classes, fields and methods
 * when they make use of reserved Java keywords.
 *
 * @author <a href="mailto:johannesd@torchmind.com">Johannes Donath</a>
 */
public class KeywordRemovalTransformer implements BytecodeTransformer {

  @SuppressWarnings("unchecked")
  private static final List<String> KEYWORDS = Arrays.asList(new String[]{
      // @formatter:off
      "abstract",       "assert",       "boolean",        "break",
      "byte",           "case",         "catch",          "char",
      "class",          "const",        "continue",       "default",
      "do",             "double",       "else",           "enum",
      "extends",        "final",        "finally",        "float",
      "for",            "goto",         "if",             "implements",
      "import",         "instanceof",   "int",            "interface",
      "long",           "native",       "new",            "package",
      "private",        "protected",    "public",         "return",
      "short",          "static",       "strictfp",       "super",
      "switch",         "synchronized", "this",           "throw",
      "throws",         "transient",    "try",            "void",
      "volatile",       "while"
      // @formatter:on
  });

  /**
   * {@inheritDoc}
   */
  @Override
  public Optional<ClassVisitor> createTransformer(@NonNull Context context, @NonNull Path source,
      @NonNull ClassVisitor nextVisitor) {
    return Optional.of(new ClassRemapper(nextVisitor, new KeywordRemapper()));
  }

  /**
   * Automatically renames all classes, fields and methods which would otherwise produce invalid
   * Java source code.
   */
  private static final class KeywordRemapper extends Remapper {

    private final MessageDigest digest;

    private KeywordRemapper() {
      try {
        this.digest = MessageDigest.getInstance("MD5");
      } catch (NoSuchAlgorithmException ex) {
        throw new RuntimeException("Illegal JVM implementation: MD5 message digest is unsupported",
            ex);
      }
    }

    /**
     * Generates a hash using the supplied unique descriptor values.
     *
     * @param elements a set of descriptors.
     * @return a hash.
     */
    @NonNull
    public String generateHash(@NonNull String... elements) {
      int i = 0;

      for (String element : elements) {
        if (i++ != 0) {
          this.digest.update((byte) 0x00);
        }

        this.digest.update(element.getBytes(StandardCharsets.UTF_8));
      }

      return Hex.encodeHexString(this.digest.digest());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String map(@NonNull String typeName) {
      if (KEYWORDS.contains(typeName)) {
        typeName = "bs_class_" + this.generateHash(typeName);
      }

      return super.map(typeName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String mapFieldName(@NonNull String owner, @NonNull String name, @NonNull String desc) {
      if (KEYWORDS.contains(name)) {
        name = "bs_field_" + this.generateHash(owner, name, desc);
      }

      return super.mapFieldName(owner, name, desc);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String mapMethodName(@NonNull String owner, @NonNull String name, @NonNull String desc) {
      if (KEYWORDS.contains(name)) {
        name = "bs_method_" + this.generateHash(owner, name, desc);
      }

      return super.mapMethodName(owner, name, desc);
    }
  }
}
