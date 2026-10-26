# JCodec (MP4 container subset)

The files under `app/src/main/java/org/jcodec/` are the MP4 container subset of
[JCodec](https://jcodec.org/), vendored inside marcoc1712's jaudiotagger fork
(`net.jthink:jaudiotagger:2.3.15`) because jaudiotagger's MP4 writer/editor needs
it. JCodec is distributed under the Simplified BSD (2 clause) license:

    Copyright (c) 2013, JCodec.org
    All rights reserved.

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions are met:

    1. Redistributions of source code must retain the above copyright notice,
       this list of conditions and the following disclaimer.
    2. Redistributions in binary form must reproduce the above copyright notice,
       this list of conditions and the following disclaimer in the documentation
       and/or other materials provided with the distribution.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
    AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
    ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
    LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
    CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
    SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
    INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
    CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
    ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
    POSSIBILITY OF SUCH DAMAGE.

Other third party components:

| Component | Version | License | Where |
| --- | --- | --- | --- |
| jaudiotagger (marcoc1712 fork) | 2.3.15 | LGPL-2.1+ | `app/src/main/java/org/jaudiotagger/`, `app/third_party/jaudiotagger/LICENSE.txt` |
| JCodec (MP4 subset) | as vendored | BSD-2-Clause | `app/src/main/java/org/jcodec/` |
| okio | 1.17.3 | Apache-2.0 | Gradle dependency |
| Chromaprint | 1.5.1 | LGPL-2.1+ | Kotlin port, `fp/third_party_chromaprint_LICENSE.md` |
| jaudiotagger test audio | - | LGPL-2.1+ (test data) | `app/src/test/resources/tag/` |
