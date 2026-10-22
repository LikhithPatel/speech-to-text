"use strict";
const API = "/api";
const TARGET_RATE = 16000;
const SEND_EVERY = 4096; // input samples gathered before each send
const $ = (id) => document.getElementById(id);
const micBtn = $("micBtn");
const statusEl = $("status");
const liveText = $("liveText");
const titleInput = $("titleInput");
const saveBtn = $("saveBtn");
const clearBtn = $("clearBtn");
const historyEl = $("history");
const editDialog = $("editDialog");
const editTitle = $("editTitle");
const editText = $("editText");
let ws = null;
let ctx = null;
let stream = null;
let node = null;
let recording = false;
let state = "idle";
let editingId = null;
/* ---------- Status ---------- */
function setState(s) {
    state = s;
    const labels = {
        idle: "Idle",
        connecting: "Connecting...",
        live: "● Listening...",
        finishing: "Finishing...",
    };
    statusEl.textContent = labels[s];
    statusEl.className = s === "live" ? "status live" : "status";
}
/* ---------- Audio capture ---------- */
// Tiny AudioWorklet (kept as a string so no extra file is needed).
const WORKLET_CODE = `
class PcmCapture extends AudioWorkletProcessor {
  process(inputs) {
    const ch = inputs[0][0];
    if (ch) this.port.postMessage(ch.slice(0));
    return true;
  }
}
registerProcessor("pcm-capture", PcmCapture);
`;
// Browser mic runs at 44.1/48 kHz; Gemini wants 16 kHz 16-bit PCM.
function downsample(input, inRate) {
    const ratio = inRate / TARGET_RATE;
    const len = Math.floor(input.length / ratio);
    const out = new Int16Array(len);
    for (let i = 0; i < len; i++) {
        const start = Math.floor(i * ratio);
        const end = Math.min(Math.floor((i + 1) * ratio), input.length);
        let sum = 0;
        for (let j = start; j < end; j++)
            sum += input[j];
        const avg = Math.max(-1, Math.min(1, sum / Math.max(1, end - start)));
        out[i] = avg < 0 ? avg * 0x8000 : avg * 0x7fff;
    }
    return out;
}
function onSocketMessage(ev) {
    const msg = JSON.parse(ev.data);
    if (msg.type === "ready") {
        if (recording)
            setState("live");
    }
    else if (msg.type === "transcript" && msg.text) {
        liveText.value += msg.text;
        liveText.scrollTop = liveText.scrollHeight;
    }
    else if (msg.type === "error") {
        console.error(msg.message);
        alert(msg.message ?? "Transcription error");
        stopRecording();
    }
}
async function startRecording() {
    try {
        stream = await navigator.mediaDevices.getUserMedia({
            audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
        });
    }
    catch {
        alert("Microphone access denied.");
        return;
    }
    recording = true;
    micBtn.textContent = "Stop Recording";
    micBtn.classList.add("recording");
    setState("connecting");
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const socket = new WebSocket(`${proto}://${location.host}/ws/stt`);
    ws = socket;
    socket.onmessage = onSocketMessage;
    socket.onclose = () => {
        if (recording && ws === socket)
            stopRecording();
    };
    ctx = new AudioContext();
    const url = URL.createObjectURL(new Blob([WORKLET_CODE], { type: "application/javascript" }));
    await ctx.audioWorklet.addModule(url);
    URL.revokeObjectURL(url);
    const source = ctx.createMediaStreamSource(stream);
    node = new AudioWorkletNode(ctx, "pcm-capture");
    const mute = ctx.createGain();
    mute.gain.value = 0;
    source.connect(node);
    node.connect(mute).connect(ctx.destination); // keeps the worklet running, silently
    const inRate = ctx.sampleRate;
    let chunks = [];
    let total = 0;
    node.port.onmessage = (e) => {
        chunks.push(e.data);
        total += e.data.length;
        if (total < SEND_EVERY)
            return;
        const merged = new Float32Array(total);
        let offset = 0;
        for (const c of chunks) {
            merged.set(c, offset);
            offset += c.length;
        }
        chunks = [];
        total = 0;
        if (state === "live" && socket.readyState === WebSocket.OPEN) {
            socket.send(downsample(merged, inRate).buffer);
        }
    };
}
function stopRecording() {
    if (!recording)
        return;
    recording = false;
    node?.disconnect();
    node = null;
    stream?.getTracks().forEach((t) => t.stop());
    stream = null;
    void ctx?.close();
    ctx = null;
    micBtn.textContent = "Start Recording";
    micBtn.classList.remove("recording");
    const socket = ws;
    ws = null;
    if (socket && socket.readyState === WebSocket.OPEN) {
        // Tell the server we're done, then stay open briefly for the last words.
        socket.send("end");
        setState("finishing");
        setTimeout(() => {
            socket.close();
            if (!recording)
                setState("idle");
        }, 2500);
    }
    else {
        setState("idle");
    }
}
/* ---------- CRUD ---------- */
async function loadHistory() {
    const res = await fetch(`${API}/transcriptions`);
    const items = await res.json();
    if (items.length === 0) {
        historyEl.innerHTML = `<div class="empty">No transcriptions yet.</div>`;
        return;
    }
    historyEl.innerHTML = "";
    for (const t of items) {
        const div = document.createElement("div");
        div.className = "item";
        const h = document.createElement("h4");
        h.textContent = t.title || "Untitled";
        const time = document.createElement("time");
        time.textContent = new Date(t.createdAt).toLocaleString();
        const p = document.createElement("p");
        p.textContent = t.text;
        const actions = document.createElement("div");
        actions.className = "actions";
        const edit = document.createElement("button");
        edit.textContent = "Edit";
        edit.onclick = () => openEdit(t);
        const del = document.createElement("button");
        del.textContent = "Delete";
        del.className = "danger";
        del.onclick = () => void removeItem(t.id);
        actions.append(edit, del);
        div.append(h, time, p, actions);
        historyEl.appendChild(div);
    }
}
async function saveCurrent() {
    const text = liveText.value.trim();
    if (!text) {
        alert("Nothing to save.");
        return;
    }
    await fetch(`${API}/transcriptions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: titleInput.value.trim() || null, text }),
    });
    liveText.value = "";
    titleInput.value = "";
    await loadHistory();
}
function openEdit(t) {
    editingId = t.id;
    editTitle.value = t.title ?? "";
    editText.value = t.text;
    editDialog.showModal();
}
async function submitEdit() {
    if (editingId === null)
        return;
    await fetch(`${API}/transcriptions/${editingId}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: editTitle.value.trim() || null, text: editText.value }),
    });
    editingId = null;
    await loadHistory();
}
async function removeItem(id) {
    if (!confirm("Delete this transcription?"))
        return;
    await fetch(`${API}/transcriptions/${id}`, { method: "DELETE" });
    await loadHistory();
}
/* ---------- Wiring ---------- */
micBtn.onclick = () => (recording ? stopRecording() : void startRecording());
saveBtn.onclick = () => void saveCurrent();
clearBtn.onclick = () => (liveText.value = "");
editDialog.addEventListener("close", () => {
    if (editDialog.returnValue === "save")
        void submitEdit();
});
void loadHistory();
