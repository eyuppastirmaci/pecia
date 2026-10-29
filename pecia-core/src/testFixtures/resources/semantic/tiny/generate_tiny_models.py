"""Writes the tiny ONNX models that stand in for all-MiniLM-L6-v2 in Pecia's embedder tests.

Run from this directory with any Python 3; it needs no packages because it encodes the ONNX protobuf messages itself:

    python3 generate_tiny_models.py

tiny.onnx has the real model's inputs and output. For token id t at position s and dimension d it returns

    last_hidden_state[b, s, d] = sin(t * (d + 1) * 1e-5 + d * 0.1)
                                 + 100 * (1 - attention_mask[b, s])
                                 + 1000 * token_type_ids[b, s]
                                 + 10000 * (number of positions in input b with token id 0 and attention mask 1)

The last term stands in for attention: like the real model, every token changes when padding is not masked. A correct
embedder that masks padding, sends token type 0, and pools only real tokens sees only the first term. The other models
each break one part of the contract.
"""
import struct

DIMENSIONS = 384
FLOAT = 1
INT64 = 7
ATTRIBUTE_INT = 2
ATTRIBUTE_INTS = 7


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def field(number, wire_type, payload):
    return varint(number << 3 | wire_type) + payload


def integer(number, value):
    return field(number, 0, varint(value & 0xFFFFFFFFFFFFFFFF))


def message(number, payload):
    return field(number, 2, varint(len(payload)) + payload)


def text(number, value):
    return message(number, value.encode("utf-8"))


def tensor(name, dims, data_type, values):
    pack = "<%df" % len(values) if data_type == FLOAT else "<%dq" % len(values)
    return b"".join(integer(1, dim) for dim in dims) + integer(2, data_type) + text(8, name) + message(
        9, struct.pack(pack, *values))


def value_info(name, element_type, dims):
    shape = b"".join(message(1, text(2, dim) if isinstance(dim, str) else integer(1, dim)) for dim in dims)
    return text(1, name) + message(2, message(1, integer(1, element_type) + message(2, shape)))


def node(op_type, inputs, outputs, **attributes):
    encoded = b"".join(text(1, name) for name in inputs) + b"".join(text(2, name) for name in outputs)
    encoded += text(4, op_type)
    for key, value in attributes.items():
        if isinstance(value, list):
            attribute = b"".join(integer(8, item) for item in value) + integer(20, ATTRIBUTE_INTS)
        else:
            attribute = integer(3, value) + integer(20, ATTRIBUTE_INT)
        encoded += message(5, text(1, key) + attribute)
    return encoded


def model(graph):
    opset = text(1, "") + integer(2, 17)
    return integer(1, 8) + text(2, "pecia-tiny-model-generator") + message(7, graph) + message(8, opset)


def build(
    name, width=DIMENSIONS, declared_width=DIMENSIONS, token_types=True, divide_by_zero=False, cut_to=None, pooled=False
):
    frequencies = [(d + 1) * 1e-5 for d in range(width)]
    phases = [d * 0.1 for d in range(width)]
    initializers = [
        tensor("frequencies", [width], FLOAT, frequencies),
        tensor("phases", [width], FLOAT, phases),
        tensor("last_axis", [1], INT64, [-1]),
        tensor("one", [], FLOAT, [1.0]),
        tensor("padding_offset", [], FLOAT, [100.0]),
        tensor("pad_id", [], INT64, [0]),
        tensor("sequence_axis", [1], INT64, [1]),
        tensor("attended_pad_offset", [], FLOAT, [10000.0]),
    ]
    if token_types:
        initializers.append(tensor("type_offset", [], FLOAT, [1000.0]))
    nodes = [
        node("Cast", ["input_ids"], ["ids"], to=FLOAT),
        node("Unsqueeze", ["ids", "last_axis"], ["ids_column"]),
        node("Mul", ["ids_column", "frequencies"], ["scaled"]),
        node("Add", ["scaled", "phases"], ["angles"]),
        node("Sin", ["angles"], ["features"]),
        node("Cast", ["attention_mask"], ["mask"], to=FLOAT),
        node("Sub", ["one", "mask"], ["padding"]),
        node("Mul", ["padding", "padding_offset"], ["padding_shift"]),
        node("Unsqueeze", ["padding_shift", "last_axis"], ["padding_column"]),
        node("Add", ["features", "padding_column"], ["masked_positions"]),
        node("Equal", ["input_ids", "pad_id"], ["is_pad"]),
        node("Cast", ["is_pad"], ["pads"], to=FLOAT),
        node("Mul", ["pads", "mask"], ["attended_pads"]),
        node("ReduceSum", ["attended_pads", "sequence_axis"], ["attended_pad_count"], keepdims=1),
        node("Mul", ["attended_pad_count", "attended_pad_offset"], ["attention_shift"]),
        node("Unsqueeze", ["attention_shift", "last_axis"], ["attention_column"]),
        node("Add", ["masked_positions", "attention_column"], ["masked"]),
    ]
    result = "masked"
    if token_types:
        nodes += [
            node("Cast", ["token_type_ids"], ["types"], to=FLOAT),
            node("Mul", ["types", "type_offset"], ["type_shift"]),
            node("Unsqueeze", ["type_shift", "last_axis"], ["type_column"]),
            node("Add", ["masked", "type_column"], ["typed"]),
        ]
        result = "typed"
    if divide_by_zero:
        nodes += [
            node("Sub", ["ids_column", "ids_column"], ["zero"]),
            node("Div", [result, "zero"], ["divided"]),
        ]
        result = "divided"
    if cut_to is not None:
        # The cut length is computed from the batch size at run time, so its width cannot be inferred when loading.
        initializers += [
            tensor("zero_length", [], INT64, [0]),
            tensor("cut_length", [1], INT64, [cut_to]),
            tensor("start", [1], INT64, [0]),
        ]
        nodes += [
            node("Shape", ["input_ids"], ["batch_size"], start=0, end=1),
            node("Mul", ["batch_size", "zero_length"], ["no_length"]),
            node("Add", ["no_length", "cut_length"], ["end"]),
            node("Slice", [result, "start", "end", "last_axis"], ["cut"]),
        ]
        result = "cut"
    output_dims = ["batch", "sequence", declared_width]
    if pooled:
        nodes.append(node("ReduceMean", [result], ["pooled"], axes=[1], keepdims=0))
        result = "pooled"
        output_dims = ["batch", declared_width]
    nodes.append(node("Identity", [result], ["last_hidden_state"]))

    inputs = ["input_ids", "attention_mask"] + (["token_type_ids"] if token_types else [])
    graph = b"".join(message(1, encoded) for encoded in nodes) + text(2, name)
    graph += b"".join(message(5, encoded) for encoded in initializers)
    graph += b"".join(message(11, value_info(input_name, INT64, ["batch", "sequence"])) for input_name in inputs)
    graph += message(12, value_info("last_hidden_state", FLOAT, output_dims))
    with open(name + ".onnx", "wb") as output:
        output.write(model(graph))


def main():
    build("tiny")
    # Declares 383 dimensions, so opening it fails.
    build("declared-width", width=383, declared_width=383)
    # Declares an unknown width but returns 383 dimensions, so inference fails.
    build("dynamic-width", declared_width="width", cut_to=383)
    # Lacks token_type_ids, so opening it fails.
    build("missing-input", token_types=False)
    # Returns one pooled vector per input instead of token embeddings, so opening it fails.
    build("pooled-output", pooled=True)
    # Divides every value by zero, so inference fails.
    build("non-finite", divide_by_zero=True)


if __name__ == "__main__":
    main()
