# Connecto Relay Proxy - Render.com Deployment Guide

This directory contains the Node.js WebSocket-to-TCP Relay Proxy for the **Connecto** Minecraft mod.

## Step-by-Step Render.com Deployment

### 1. Push to GitHub
Make sure this repository (or just this `relay-proxy` folder) is pushed to your GitHub account.

### 2. Deploy on Render.com
1. Go to [Render.com](https://render.com) and sign up / log in.
2. Click **New +** -> **Web Service**.
3. Connect your GitHub repository.
4. Fill in the following settings:
   - **Name**: `connecto-relay` (or whatever you prefer)
   - **Root Directory**: `relay-proxy`
   - **Runtime**: `Node`
   - **Build Command**: `npm install`
   - **Start Command**: `node index.js`
   - **Instance Type**: `Free`
5. Click **Create Web Service**.

### 3. Get Your Proxy URL
Once deployed, Render will give you a URL like:
`https://connecto-relay.onrender.com`

Replace `https://` with `wss://` for WebSocket usage:
`wss://connecto-relay.onrender.com`

Pass this URL into the `Connecto` mod configuration!
