#!/usr/bin/env python3
"""
Rebuilds a plain float32 ONNX graph from an app model quantised with MatMulNBits (8-bit weights),
so it can be re-quantised for the Qualcomm NPU (whose ONNX Runtime backend cannot run MatMulNBits).

Usage: python tools/npu_dequant.py <in.onnx> <out.onnx>
"""
import sys

import numpy as np
import onnx
from onnx import helper, numpy_helper


def main(src, dst):
    m = onnx.load(src)
    g = m.graph
    inits = {i.name: i for i in g.initializer}
    used = set()
    new_nodes = []
    for n in g.node:
        if n.op_type != "MatMulNBits":
            new_nodes.append(n)
            continue
        a = {x.name: helper.get_attribute_value(x) for x in n.attribute}
        K, N, bits, bs = a["K"], a["N"], a.get("bits", 4), a["block_size"]
        assert bits == 8, bits
        q = numpy_helper.to_array(inits[n.input[1]]).reshape(N, -1)[:, :((K + bs - 1) // bs) * bs].astype(np.float32)
        nb = (K + bs - 1) // bs
        scales = numpy_helper.to_array(inits[n.input[2]]).astype(np.float32).reshape(N, nb)
        if len(n.input) > 3 and n.input[3]:
            zp = numpy_helper.to_array(inits[n.input[3]]).astype(np.float32).reshape(N, -1)[:, :nb]
            used.add(n.input[3])
        else:
            zp = np.full((N, nb), 2 ** (bits - 1), np.float32)
        w = (q.reshape(N, nb, bs) - zp[:, :, None]) * scales[:, :, None]
        w = w.reshape(N, nb * bs)[:, :K].T.copy()  # [K, N]
        wname = n.input[1] + "_f32"
        g.initializer.append(numpy_helper.from_array(w, wname))
        used.update([n.input[1], n.input[2]])
        bias = n.input[5] if len(n.input) > 5 and n.input[5] else None
        if bias:
            mid = n.output[0] + "_mm"
            new_nodes.append(helper.make_node("MatMul", [n.input[0], wname], [mid], name=n.name + "_mm"))
            new_nodes.append(helper.make_node("Add", [mid, bias], [n.output[0]], name=n.name + "_bias"))
        else:
            new_nodes.append(helper.make_node("MatMul", [n.input[0], wname], [n.output[0]], name=n.name))
    for name in used:
        g.initializer.remove(inits[name])
    del g.node[:]
    g.node.extend(new_nodes)
    keep = [o for o in m.opset_import if o.domain != "com.microsoft"]
    del m.opset_import[:]
    m.opset_import.extend(keep)
    onnx.checker.check_model(m)
    onnx.save_model(m, dst)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
