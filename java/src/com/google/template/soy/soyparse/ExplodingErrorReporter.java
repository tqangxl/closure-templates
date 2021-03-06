/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.soyparse;

import com.google.template.soy.base.SourceLocation;

/**
 * {@link ErrorReporter} implementation that throws an {@link AssertionError} whenever an error
 * is reported to it. This is never desirable in production code (which is why it is under
 * javatests), but often desirable in tests, which should fail in the presence of any errors
 * that are not specifically checked for.
 *
 * <p>To write a test that does not have this exploding behavior (for example, a test that needs
 * to check the full list of errors encountered during compilation), pass a non-exploding
 * ErrorReporter instance to
 * {@link com.google.template.soy.SoyFileSetParserBuilder#errorReporter}.
 *
 * @author brndn@google.com (Brendan Linn)
 */
public final class ExplodingErrorReporter extends ErrorReporterImpl {

  private static final ErrorReporter INSTANCE = new ExplodingErrorReporter();

  private ExplodingErrorReporter() {}

  @Override
  public void report(SourceLocation sourceLocation, SoyError error, String... args) {
    throw new AssertionError(
        String.format("Unexpected SoyError: %s at %s", error.format(args), sourceLocation));
  }

  public static ErrorReporter get() {
    return INSTANCE;
  }
}
