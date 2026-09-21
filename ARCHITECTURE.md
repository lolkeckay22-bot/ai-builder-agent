# Architecture

Android client → HTTPS agent API → isolated task workspace → LLM provider → tools → build runner → verified artifact → Android client.

The client never executes untrusted builds locally and never embeds provider or GitHub secrets. Each task uses isolated `input`, `working`, `output`, and `logs` directories on the agent side.
