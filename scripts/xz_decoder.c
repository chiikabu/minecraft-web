/* SPDX-License-Identifier: Apache-2.0 */
#include <stdlib.h>
#include "xz.h"

int decode(const unsigned char *input, size_t input_size,
           unsigned char *output, size_t output_size) {
    struct xz_buf buffer = {
        .in = input, .in_size = input_size,
        .out = output, .out_size = output_size,
    };
    xz_crc32_init();
    struct xz_dec *decoder = xz_dec_init(XZ_SINGLE, 0);
    if (decoder == NULL) return XZ_MEM_ERROR;
    enum xz_ret result = xz_dec_run(decoder, &buffer);
    xz_dec_end(decoder);
    if (result == XZ_STREAM_END &&
        (buffer.in_pos != input_size || buffer.out_pos != output_size)) {
        return XZ_DATA_ERROR;
    }
    return result;
}
