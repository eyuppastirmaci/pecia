# MiniLM reference outputs

`generate_reference.py` runs sentence-transformers with all-MiniLM-L6-v2 at the revision Pecia pins and writes what the
tests compare against:

- `minilm-token-ids.toml`: the model input ids Hugging Face's fast tokenizer produces for each case, truncated to the
  model's 256-token limit;
- `minilm-embeddings.toml`: the normalized 384-dimensional sentence embeddings for the same cases.

Each file records the library versions that produced it. Never edit the outputs by hand. To change them, edit the cases
in the generator and run it from this directory in a virtual environment with `sentence-transformers` installed:

```sh
python generate_reference.py
```

Pecia's tokenizer classifies characters with Java's Unicode tables, which are newer than those of Hugging Face's
tokenizer, so a few hundred recently added or recategorized characters can tokenize differently. The cases avoid them.
