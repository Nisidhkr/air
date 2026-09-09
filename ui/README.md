# Air UI

This is the frontend UI for the Air P2P file sharing application. It's built with Next.js, TypeScript, and Tailwind CSS.

## Features

- Drag and drop file upload
- File sharing via invite codes (port numbers)
- File downloading using invite codes
- Modern, responsive UI

## Prerequisites

- Node.js 18+ and npm
- Java 11+ (for the backend)

## Getting Started

### Install Dependencies

```bash
cd ui
npm install
```

### Development Server

```bash
npm run dev
```

This will start the development server on [http://localhost:3000](http://localhost:3000).

### Build for Production

```bash
npm run build
```

### Start Production Server

```bash
npm start
```

## How to Use

1. **Share a File**:
   - Go to the "Share a File" tab
   - Drag and drop a file or click to select one
   - Once uploaded, you'll receive an invite code (port number)
   - Share this invite code with anyone you want to share the file with

2. **Receive a File**:
   - Go to the "Receive a File" tab
   - Enter the invite code you received
   - Click "Download File"
   - The file will be downloaded to your device

## Backend Integration

The UI communicates with the Fylo Local Agent at `127.0.0.1:7000` by default.
Make sure the agent is running before using Nearby transfers.

To start the backend server:

```bash
cd ..  # Go back to the project root
mvn clean package
java -jar target/p2p-1.0-SNAPSHOT.jar 7000
```

## Project Structure

- `src/app`: Next.js app router pages
- `src/components`: React components
- `public`: Static assets
