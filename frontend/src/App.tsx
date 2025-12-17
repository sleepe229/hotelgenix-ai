import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { v4 as uuidv4 } from 'uuid';
import './App.css';

interface HotelCard {
    id?: string;
    name: string;
    country: string;
    city: string;
    stars?: number;
    pricePerNight?: number;
    rating?: number;
    similarity?: number;
    description?: string;
    kidsClub?: boolean;
    allInclusive?: boolean;
    aquapark?: boolean;
}

interface Message {
    id: string;
    content: string;
    sender: 'user' | 'assistant';
    timestamp: number;
    type?: 'text' | 'hotel_card' | 'error' | 'comparison';
    hotel?: HotelCard;
    comparison?: {
        hotel1: HotelCard;
        hotel2: HotelCard;
        difference: number;
        cheaper: string;
    };
}

function App() {
    const [messages, setMessages] = useState<Message[]>([]);
    const [input, setInput] = useState('');
    const [isConnected, setIsConnected] = useState(false);
    const [isTyping, setIsTyping] = useState(false);
    const [isRecording, setIsRecording] = useState(false);
    const [recordingTime, setRecordingTime] = useState(0);
    const [uploadedFiles, setUploadedFiles] = useState<File[]>([]);

    const messagesEndRef = useRef<HTMLDivElement>(null);
    const clientRef = useRef<Client | null>(null);
    const mediaRecorderRef = useRef<MediaRecorder | null>(null);
    // const audioChunksRef = useRef<Blob[]>([]);
    const imageInputRef = useRef<HTMLInputElement>(null);
    const documentInputRef = useRef<HTMLInputElement>(null);
    const recordingIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

    const scrollToBottom = () => {
        messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    };

    useEffect(() => {
        scrollToBottom();
    }, [messages]);

    /**
     * 🔌 WebSocket подключение
     */
    useEffect(() => {
        const client = new Client({
            brokerURL: 'ws://localhost:8080/ws-chat/websocket',
            reconnectDelay: 5000,
            heartbeatIncoming: 4000,
            heartbeatOutgoing: 4000,
            debug: (str) => console.log('[STOMP]', str),
        });

        client.onConnect = () => {
            setIsConnected(true);
            console.log('✅ WebSocket connected');

            client.subscribe('/topic/messages', (message) => {
                const chatMessage = JSON.parse(message.body);
                handleIncomingMessage(chatMessage);
            });
        };

        client.onStompError = (frame) => {
            console.error('❌ STOMP error:', frame.headers['message']);
            setIsConnected(false);
        };

        client.onDisconnect = () => {
            console.log('🔌 WebSocket disconnected');
            setIsConnected(false);
        };

        client.activate();
        clientRef.current = client;

        return () => {
            if (client.active) {
                client.deactivate();
            }
        };
    }, []);

    /**
     * 📨 Обработка входящих сообщений от backend
     */
    const handleIncomingMessage = (chatMessage: any) => {
        if (!chatMessage || (!chatMessage.content && !chatMessage.hotelData && !chatMessage.comparisonData)) {
            return;
        }

        // 🏨 Отельные карточки
        if (chatMessage.type === 'hotel_card' && chatMessage.hotelData) {
            setMessages(prev => [...prev, {
                id: uuidv4(),
                content: '',
                sender: 'assistant',
                timestamp: chatMessage.timestamp || Date.now(),
                type: 'hotel_card',
                hotel: chatMessage.hotelData as HotelCard
            }]);
            setIsTyping(false);
            return;
        }

        // 📊 Сравнение документов
        if (chatMessage.type === 'comparison' && chatMessage.comparisonData) {
            setMessages(prev => [...prev, {
                id: uuidv4(),
                content: `Сравнение: ${chatMessage.comparisonData.hotel1.name} vs ${chatMessage.comparisonData.hotel2.name}`,
                sender: 'assistant',
                timestamp: chatMessage.timestamp || Date.now(),
                type: 'comparison',
                comparison: chatMessage.comparisonData
            }]);
            setIsTyping(false);
            return;
        }

        // 👤 Сообщения от пользователя
        if (chatMessage.sender === 'user') {
            setMessages(prev => [...prev, {
                id: uuidv4(),
                content: chatMessage.content,
                sender: 'user',
                timestamp: chatMessage.timestamp || Date.now(),
                type: 'text'
            }]);
            setIsTyping(false);
            return;
        }

        // ❌ Ошибки
        if (chatMessage.type === 'error') {
            setMessages(prev => [...prev, {
                id: uuidv4(),
                content: chatMessage.content,
                sender: 'assistant',
                timestamp: chatMessage.timestamp || Date.now(),
                type: 'error'
            }]);
            setIsTyping(false);
            return;
        }

        // 💬 Текстовые сообщения (с потоком)
        if (chatMessage.content && chatMessage.sender === 'assistant') {
            setIsTyping(true);
            setMessages(prev => {
                const last = prev[prev.length - 1];
                if (last?.sender === 'assistant' && last.type === 'text' && !last.hotel && !last.comparison) {
                    return [...prev.slice(0, -1), {
                        ...last,
                        content: last.content + chatMessage.content
                    }];
                }
                return [...prev, {
                    id: uuidv4(),
                    content: chatMessage.content,
                    sender: 'assistant',
                    timestamp: chatMessage.timestamp || Date.now(),
                    type: 'text'
                }];
            });
        }
    };

    /**
     * 📤 Отправка текстового сообщения
     */
    const sendMessage = () => {
        if (!input.trim() || !clientRef.current?.connected) {
            return;
        }

        const userMessage = {
            content: input.trim(),
            sender: 'user',
            timestamp: Date.now()
        };

        console.log('[CHAT] Sending:', userMessage.content);
        clientRef.current?.publish({
            destination: '/app/chat',
            body: JSON.stringify(userMessage)
        });

        setInput('');
    };

    /**
     * 📷 Загрузка изображения отеля
     */
    const handleImageUpload = (event: React.ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0];
        if (!file) return;

        console.log('[VISION] Uploading image:', file.name);

        const formData = new FormData();
        formData.append('file', file);

        fetch('http://localhost:8080/api/upload/image', {
            method: 'POST',
            body: formData
        })
            .then(res => {
                if (!res.ok) throw new Error('Upload failed');
                console.log('[VISION] Image uploaded successfully');
                // Сообщение об успехе приходит через WebSocket
            })
            .catch(err => {
                console.error('[VISION] Error:', err);
                setMessages(prev => [...prev, {
                    id: uuidv4(),
                    content: '❌ Ошибка загрузки изображения: ' + err.message,
                    sender: 'assistant',
                    timestamp: Date.now(),
                    type: 'error'
                }]);
            });

        // Очищаем input
        if (imageInputRef.current) {
            imageInputRef.current.value = '';
        }
    };

    /**
     * 📄 Загрузка документов (PDF, DOCX, TXT) для сравнения
     */
    const handleDocumentUpload = (event: React.ChangeEvent<HTMLInputElement>) => {
        const files = event.target.files;
        if (!files) return;

        for (let i = 0; i < files.length; i++) {
            const file = files[i];
            const ext = file.name.split('.').pop()?.toLowerCase();

            // Проверяем формат
            if (!['pdf', 'docx', 'txt'].includes(ext || '')) {
                setMessages(prev => [...prev, {
                    id: uuidv4(),
                    content: `❌ Неподдерживаемый формат: ${ext}. Загружай PDF, DOCX или TXT`,
                    sender: 'assistant',
                    timestamp: Date.now(),
                    type: 'error'
                }]);
                continue;
            }

            console.log('[PARSER] Uploading document:', file.name);

            const formData = new FormData();
            formData.append('file', file);

            fetch('http://localhost:8080/api/upload/document', {
                method: 'POST',
                body: formData
            })
                .then(res => {
                    if (!res.ok) throw new Error('Upload failed');
                    console.log('[PARSER] Document uploaded successfully:', file.name);

                    // Добавляем в список загруженных файлов
                    setUploadedFiles(prev => [...prev, file]);

                    // Если загружено 2 файла, предлагаем их сравнить
                    if (uploadedFiles.length === 1) {
                        setMessages(prev => [...prev, {
                            id: uuidv4(),
                            content: `📄 Загруженный второй документ. Хочешь сравнить цены между "${uploadedFiles[0].name}" и "${file.name}"?`,
                            sender: 'assistant',
                            timestamp: Date.now(),
                            type: 'text'
                        }]);
                    }
                })
                .catch(err => {
                    console.error('[PARSER] Error:', err);
                    setMessages(prev => [...prev, {
                        id: uuidv4(),
                        content: `❌ Ошибка загрузки документа "${file.name}": ${err.message}`,
                        sender: 'assistant',
                        timestamp: Date.now(),
                        type: 'error'
                    }]);
                });
        }

        // Очищаем input
        if (documentInputRef.current) {
            documentInputRef.current.value = '';
        }
    };

    /**
     * 🎤 Начать запись голоса
     */
    const startRecording = async () => {
        try {
            console.log('[AUDIO] Requesting microphone access...');

            const stream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true
                }
            });

            console.log('[AUDIO] ✅ Microphone access granted');

            const audioContext = new AudioContext();
            const systemSampleRate = audioContext.sampleRate;
            const targetSampleRate = 16000;

            console.log('[AUDIO] System sample rate:', systemSampleRate, 'Hz');
            console.log('[AUDIO] Target sample rate:', targetSampleRate, 'Hz');

            const mediaStreamSource = audioContext.createMediaStreamSource(stream);

            await audioContext.audioWorklet.addModule(
                'data:application/javascript,' + encodeURIComponent(getAudioProcessorCode())
            );

            const workletNode = new AudioWorkletNode(audioContext, 'pcm-processor', {
                processorOptions: {
                    systemSampleRate,
                    targetSampleRate
                }
            });

            const audioChunks: Float32Array[] = [];

            workletNode.port.onmessage = (event) => {
                audioChunks.push(event.data);
            };

            mediaStreamSource.connect(workletNode);

            mediaRecorderRef.current = {
                stop: () => {
                    mediaStreamSource.disconnect();
                    workletNode.disconnect();
                    stream.getTracks().forEach(track => track.stop());
                    audioContext.close();

                    if (audioChunks.length === 0) {
                        console.warn('[AUDIO] ⚠️ No audio chunks recorded');
                        setMessages(prev => [...prev, {
                            id: uuidv4(),
                            content: '❌ Аудио не записано. Попробуйте снова.',
                            sender: 'assistant',
                            timestamp: Date.now(),
                            type: 'error'
                        }]);
                        return;
                    }

                    const pcmData = convertFloat32ToInt16PCM(audioChunks);
                    const blob = new Blob([pcmData], { type: 'audio/x-pcm' });

                    console.log('[AUDIO] ✅ Записано', audioChunks.length, 'chunks');
                    console.log('[AUDIO] PCM размер:', pcmData.byteLength, 'bytes');
                    console.log('[AUDIO] Длительность:', (pcmData.byteLength / 2 / targetSampleRate).toFixed(2), 'сек');

                    sendPCMToBackend(blob);
                },
                stream
            } as any;

            setIsRecording(true);
            setRecordingTime(0);

            recordingIntervalRef.current = setInterval(() => {
                setRecordingTime(prev => prev + 1);
            }, 1000);

        } catch (error: any) {
            console.error('[AUDIO] ❌ Error:', error);

            let errorMsg = '❌ Ошибка микрофона: ' + error.message;

            if (error.name === 'NotAllowedError') {
                errorMsg = '❌ Доступ к микрофону запрещён. Разрешите доступ в настройках браузера.';
            } else if (error.name === 'NotFoundError') {
                errorMsg = '❌ Микрофон не найден. Подключите микрофон и перезагрузите страницу.';
            } else if (error.name === 'NotReadableError') {
                errorMsg = '❌ Микрофон занят другим приложением. Закройте другие приложения и попробуйте снова.';
            }

            setMessages(prev => [...prev, {
                id: uuidv4(),
                content: errorMsg,
                sender: 'assistant',
                timestamp: Date.now(),
                type: 'error'
            }]);
        }
    };

    /**
     * 📝 AudioWorklet код для захвата и ресэмплирования PCM
     */
    function getAudioProcessorCode(): string {
        return `
        class PCMProcessor extends AudioWorkletProcessor {
            constructor(options) {
                super(options);
                this.systemSampleRate = options.processorOptions.systemSampleRate;
                this.targetSampleRate = options.processorOptions.targetSampleRate;
                this.resampleRatio = this.targetSampleRate / this.systemSampleRate;
                
                console.log('[AudioWorklet] Init:', {
                    systemSampleRate: this.systemSampleRate,
                    targetSampleRate: this.targetSampleRate,
                    ratio: this.resampleRatio
                });
            }

            resample(input) {
                const output = [];
                const ratio = this.resampleRatio;
                let pos = 0;
                
                while (pos < input.length) {
                    const posInt = Math.floor(pos);
                    const posFrac = pos - posInt;
                    
                    if (posInt + 1 < input.length) {
                        const sample = input[posInt] * (1 - posFrac) + input[posInt + 1] * posFrac;
                        output.push(sample);
                    } else {
                        output.push(input[posInt]);
                    }
                    
                    pos += 1 / ratio;
                }
                
                return new Float32Array(output);
            }

            process(inputs, outputs) {
                const input = inputs[0];
                
                if (input && input.length > 0) {
                    const channelData = input[0];
                    
                    let processedData;
                    if (Math.abs(this.resampleRatio - 1.0) > 0.01) {
                        processedData = this.resample(channelData);
                    } else {
                        processedData = new Float32Array(channelData);
                    }
                    
                    this.port.postMessage(processedData);
                }
                
                return true;
            }
        }
        
        registerProcessor('pcm-processor', PCMProcessor);
    `;
    }

    /**
     * 🔄 Конвертируем Float32 PCM в Int16
     */
    function convertFloat32ToInt16PCM(float32Chunks: Float32Array[]): ArrayBuffer {
        let totalLength = 0;
        for (const chunk of float32Chunks) {
            totalLength += chunk.length;
        }

        console.log('[AUDIO] Конвертация Float32 chunks:', {
            chunkCount: float32Chunks.length,
            totalSamples: totalLength,
            totalBytes: totalLength * 2,
            durationSec: (totalLength / 16000).toFixed(2)
        });

        const int16Data = new Int16Array(totalLength);
        let offset = 0;

        for (const chunk of float32Chunks) {
            for (let i = 0; i < chunk.length; i++) {
                const s = Math.max(-1, Math.min(1, chunk[i]));
                int16Data[offset++] = s < 0 ? s * 0x8000 : s * 0x7FFF;
            }
        }

        return int16Data.buffer;
    }

    /**
     * 📤 Отправляем PCM на backend
     */
    function sendPCMToBackend(blob: Blob) {
        const formData = new FormData();
        formData.append('audio', blob, `recording_${Date.now()}.pcm`);

        console.log('[AUDIO] Отправляем PCM:', blob.size, 'bytes');

        fetch('http://localhost:8080/api/upload/audio', {
            method: 'POST',
            body: formData
        })
            .then(res => {
                if (!res.ok) throw new Error('Upload failed');
                console.log('[AUDIO] ✅ PCM загружен успешно');
            })
            .catch(err => {
                console.error('[AUDIO] ❌ Ошибка загрузки:', err);
            });
    }

    /**
     * ⏹️ Остановить запись
     */
    const stopRecording = () => {
        if (!mediaRecorderRef.current) return;

        (mediaRecorderRef.current as any).stop();
        setIsRecording(false);
        if (recordingIntervalRef.current) {
            clearInterval(recordingIntervalRef.current);
        }
    };


    /**
     * ⏹️ Остановить запись и отправить
     */
    // const stopRecording = () => {
    //     if (!mediaRecorderRef.current) return;
    //
    //     mediaRecorderRef.current.onstop = async () => {
    //         try {
    //             // ✅ Получаем WAV blob
    //             const audioBlob = new Blob(audioChunksRef.current, { type: 'audio/wav' });
    //
    //             // ✅ Конвертируем WAV → PCM
    //             const pcmBlob = await convertWavToPcm(audioBlob);
    //
    //             const formData = new FormData();
    //             formData.append('audio', pcmBlob, `recording_${Date.now()}.pcm`);
    //
    //             console.log('[AUDIO] Отправляем PCM:', pcmBlob.size, 'bytes');
    //
    //             fetch('http://localhost:8080/api/upload/audio', {
    //                 method: 'POST',
    //                 body: formData
    //             })
    //                 .then(res => {
    //                     if (!res.ok) throw new Error('Upload failed');
    //                     console.log('[AUDIO] ✅ PCM загружен');
    //                 })
    //                 .catch(err => {
    //                     console.error('[AUDIO] Ошибка:', err);
    //                 });
    //         } catch (err) {
    //             console.error('[AUDIO] Ошибка конвертации:', err);
    //         }
    //     };
    //
    //     mediaRecorderRef.current.stop();
    //     mediaRecorderRef.current.stream.getTracks().forEach(track => track.stop());
    //
    //     setIsRecording(false);
    //     if (recordingIntervalRef.current) {
    //         clearInterval(recordingIntervalRef.current);
    //     }
    // };

    // /**
    //  * 🎧 Конвертируем WAV → PCM (сырые PCM данные без заголовков)
    //  */
    // /**
    //  * 🎧 Конвертируем WAV → PCM (сырые PCM данные без заголовков)
    //  */
    // async function convertWavToPcm(wavBlob: Blob): Promise<Blob> {
    //     return new Promise((resolve, reject) => {
    //         const reader = new FileReader();
    //
    //         reader.onload = async (event) => {
    //             try {
    //                 const arrayBuffer = event.target?.result as ArrayBuffer;
    //                 const view = new Uint8Array(arrayBuffer);
    //
    //                 console.log('[AUDIO] WAV size:', arrayBuffer.byteLength, 'bytes');
    //                 console.log('[AUDIO] WAV header:', Array.from(view.slice(0, 12)).map(x => String.fromCharCode(x)).join(''));
    //
    //                 // 🔍 Ищем "data" chunk (0x64617461 = "data" в ASCII)
    //                 let dataOffset = -1;
    //                 let dataSize = 0;
    //
    //                 for (let i = 0; i < view.length - 8; i++) {
    //                     // Проверяем "data" в ASCII
    //                     if (view[i] === 0x64 && view[i+1] === 0x61 && view[i+2] === 0x74 && view[i+3] === 0x61) {
    //                         console.log('[AUDIO] ✅ Found "data" chunk at offset:', i);
    //
    //                         // Размер данных (little-endian, 4 байта после "data")
    //                         dataOffset = i + 8;
    //                         dataSize = view[i+4] | (view[i+5] << 8) | (view[i+6] << 16) | (view[i+7] << 24);
    //
    //                         console.log('[AUDIO] Data offset:', dataOffset, 'Data size:', dataSize);
    //                         break;
    //                     }
    //                 }
    //
    //                 if (dataOffset === -1) {
    //                     // Fallback: если не найден chunk, отправляем все после 44 байт (стандартный WAV заголовок)
    //                     console.warn('[AUDIO] ⚠️ "data" chunk not found, используем fallback (offset 44)');
    //                     dataOffset = 44;
    //                     dataSize = arrayBuffer.byteLength - 44;
    //                 }
    //
    //                 if (dataSize <= 0) {
    //                     throw new Error('Invalid PCM data size: ' + dataSize);
    //                 }
    //
    //                 // 📊 Извлекаем только PCM данные (без WAV заголовка)
    //                 const pcmData = arrayBuffer.slice(dataOffset, dataOffset + dataSize);
    //                 const pcmBlob = new Blob([pcmData], { type: 'audio/x-pcm' });
    //
    //                 console.log('[AUDIO] ✅ Конвертация: WAV', wavBlob.size, '→ PCM', pcmBlob.size);
    //                 resolve(pcmBlob);
    //
    //             } catch (err) {
    //                 console.error('[AUDIO] Ошибка конвертации:', err);
    //                 reject(err);
    //             }
    //         };
    //
    //         reader.onerror = () => reject(new Error('FileReader error'));
    //         reader.readAsArrayBuffer(wavBlob);
    //     });
    // }
    //
    //

    const formatTime = (seconds: number) => {
        const mins = Math.floor(seconds / 60);
        const secs = seconds % 60;
        return `${mins}:${secs.toString().padStart(2, '0')}`;
    };

    return (
        <div className="flex flex-col h-screen bg-gray-50">
            {/* Header */}
            <div className="bg-gradient-to-r from-blue-600 to-indigo-700 text-white p-4 shadow-lg">
                <h1 className="text-2xl font-bold text-center">🏨 HotelGenix AI – Умный поиск отелей</h1>
                <div className="text-center text-sm opacity-90 mt-2">
                    {isConnected ? (
                        <span className="text-green-300">🟢 Подключено к серверу</span>
                    ) : (
                        <span className="text-red-300">🔴 Подключение...</span>
                    )}
                </div>
            </div>

            {/* Messages */}
            <div className="flex-1 overflow-y-auto p-4 space-y-4">
                {messages.length === 0 && (
                    <div className="flex items-center justify-center h-full text-gray-500">
                        <div className="text-center">
                            <h2 className="text-xl font-semibold mb-2">Добро пожаловать в HotelGenix! 👋</h2>
                            <p className="mb-4">Найди отель, загрузи фото, сравни прайсы или запиши голос!</p>
                            <div className="text-xs text-gray-400 space-y-1">
                                <p>📷 Загрузи фото отеля для анализа</p>
                                <p>📄 Сравни цены из PDF/DOCX/TXT документов</p>
                                <p>🎤 Запиши голос для поиска</p>
                            </div>
                        </div>
                    </div>
                )}

                {messages.map((msg) => (
                    <div key={msg.id}>
                        {msg.type === 'hotel_card' && msg.hotel ? (
                            <HotelCardComponent hotel={msg.hotel} />
                        ) : msg.type === 'comparison' && msg.comparison ? (
                            <ComparisonComponent comparison={msg.comparison} />
                        ) : msg.type === 'error' ? (
                            <div className="flex justify-start">
                                <div className="max-w-lg px-5 py-3 rounded-2xl bg-red-50 border border-red-200 text-red-700 shadow-md">
                                    {msg.content}
                                </div>
                            </div>
                        ) : (
                            <div className={`flex ${msg.sender === 'user' ? 'justify-end' : 'justify-start'}`}>
                                <div className={`max-w-lg px-5 py-3 rounded-2xl ${
                                    msg.sender === 'user'
                                        ? 'bg-blue-600 text-white'
                                        : 'bg-white border border-gray-200 text-gray-800 shadow-md'
                                }`}>
                                    {msg.content || '...'}
                                </div>
                            </div>
                        )}
                    </div>
                ))}

                {isTyping && (
                    <div className="flex justify-start">
                        <div className="bg-white border border-gray-200 px-5 py-3 rounded-2xl shadow-md">
                            <div className="flex space-x-2">
                                <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce"></div>
                                <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }}></div>
                                <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }}></div>
                            </div>
                        </div>
                    </div>
                )}

                <div ref={messagesEndRef} />
            </div>

            {/* Input + Buttons */}
            <div className="border-t bg-white p-4 shadow-lg">
                <div className="flex gap-2 max-w-4xl mx-auto mb-3">
                    {/* Загрузка фото */}
                    <button
                        onClick={() => imageInputRef.current?.click()}
                        disabled={!isConnected}
                        className="px-4 py-3 bg-cyan-500 text-white rounded-full hover:bg-cyan-600 disabled:opacity-50 transition"
                        title="Загрузить фото отеля"
                    >
                        📷
                    </button>
                    <input
                        ref={imageInputRef}
                        type="file"
                        accept="image/*"
                        onChange={handleImageUpload}
                        style={{ display: 'none' }}
                    />

                    {/* Загрузка документов */}
                    <button
                        onClick={() => documentInputRef.current?.click()}
                        disabled={!isConnected}
                        className="px-4 py-3 bg-purple-500 text-white rounded-full hover:bg-purple-600 disabled:opacity-50 transition"
                        title="Загрузить PDF/DOCX/TXT для сравнения"
                    >
                        📄
                    </button>
                    <input
                        ref={documentInputRef}
                        type="file"
                        accept=".pdf,.docx,.txt"
                        onChange={handleDocumentUpload}
                        multiple
                        style={{ display: 'none' }}
                    />

                    {/* Микрофон */}
                    <button
                        onClick={isRecording ? stopRecording : startRecording}
                        disabled={!isConnected}
                        className={`px-4 py-3 rounded-full text-white font-medium transition ${
                            isRecording
                                ? 'bg-red-500 hover:bg-red-600 animate-pulse'
                                : 'bg-green-500 hover:bg-green-600'
                        }`}
                        title={isRecording ? 'Остановить запись' : 'Начать запись'}
                    >
                        {isRecording ? `⏹️ ${formatTime(recordingTime)}` : '🎤'}
                    </button>

                    {/* Текстовый ввод */}
                    <input
                        type="text"
                        value={input}
                        onChange={(e) => setInput(e.target.value)}
                        onKeyDown={(e) => {
                            if (e.key === 'Enter') {
                                sendMessage();
                            }
                        }}
                        placeholder="Найди отель в Турции до 8000..."
                        className="flex-1 px-5 py-3 border border-gray-300 rounded-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                        disabled={!isConnected}
                    />

                    {/* Отправить */}
                    <button
                        onClick={sendMessage}
                        disabled={!isConnected || !input.trim()}
                        className="px-8 py-3 bg-blue-600 text-white font-medium rounded-full hover:bg-blue-700 disabled:opacity-50 transition"
                    >
                        ✈️
                    </button>
                </div>

                <div className="text-center text-xs text-gray-500">
                    💡 Совет: загрузи фото отеля или документы для поиска и сравнения
                </div>
            </div>
        </div>
    );
}

/**
 * 🏨 Компонент отельной карточки
 */
function HotelCardComponent({ hotel }: { hotel: HotelCard }) {
    const price = hotel.pricePerNight || 0;
    const similarity = hotel.similarity ? Math.min(Math.round(hotel.similarity * 100), 100) : 0;
    const stars = hotel.stars || 0;
    const rating = hotel.rating || 0;
    const description = (hotel.description || 'Описание недоступно').substring(0, 150);

    return (
        <div className="flex justify-center">
            <div className="w-full max-w-md bg-white rounded-xl shadow-lg overflow-hidden hover:shadow-2xl transition duration-300 border border-gray-100">
                <div className="bg-gradient-to-r from-blue-500 to-indigo-600 text-white p-4">
                    <h3 className="text-lg font-bold truncate">{hotel.name || 'Неизвестный отель'}</h3>
                    <div className="flex justify-between items-center mt-3">
                        <span className="text-sm font-medium">
                            {'⭐'.repeat(Math.min(stars, 5))}
                            {rating > 0 && <span className="ml-2">({rating.toFixed(1)}/5)</span>}
                        </span>
                        <span className="text-2xl font-bold">${price.toLocaleString('en-US')}</span>
                    </div>
                </div>

                <div className="px-4 py-2 bg-gray-50 border-b border-gray-200">
                    <p className="text-sm text-gray-700 font-medium">
                        📍 {hotel.city || 'N/A'}, {hotel.country || 'N/A'}
                    </p>
                </div>

                <div className="p-4">
                    <p className="text-gray-700 text-sm leading-relaxed">{description}...</p>
                </div>

                {(hotel.kidsClub || hotel.allInclusive || hotel.aquapark) && (
                    <div className="px-4 py-3 bg-gray-50 border-t border-gray-200 flex flex-wrap gap-2">
                        {hotel.kidsClub && (
                            <span className="px-3 py-1 bg-green-100 text-green-800 text-xs rounded-full font-medium">
                                👨‍👩‍👧 Детский клуб
                            </span>
                        )}
                        {hotel.allInclusive && (
                            <span className="px-3 py-1 bg-blue-100 text-blue-800 text-xs rounded-full font-medium">
                                🍽️ All-inclusive
                            </span>
                        )}
                        {hotel.aquapark && (
                            <span className="px-3 py-1 bg-cyan-100 text-cyan-800 text-xs rounded-full font-medium">
                                💦 Аквапарк
                            </span>
                        )}
                    </div>
                )}

                <div className="px-4 py-3 bg-gray-50 border-t border-gray-200 flex items-center justify-between">
                    <span className="text-xs text-gray-600">🎯 Соответствие</span>
                    <div className="w-20 bg-gray-300 rounded-full h-2 overflow-hidden">
                        <div
                            className={`h-full rounded-full transition-all duration-300 ${
                                similarity >= 80 ? 'bg-green-500' :
                                    similarity >= 60 ? 'bg-yellow-500' :
                                        'bg-orange-500'
                            }`}
                            style={{ width: `${similarity}%` }}
                        />
                    </div>
                    <span className="text-xs font-semibold text-gray-700 ml-2">{similarity}%</span>
                </div>

                <div className="px-4 py-4 bg-white border-t border-gray-200 flex gap-2">
                    <button className="flex-1 px-4 py-2 bg-blue-600 text-white rounded-lg font-medium hover:bg-blue-700 transition text-sm">
                        📖 Подробнее
                    </button>
                    <button className="flex-1 px-4 py-2 bg-gray-200 text-gray-700 rounded-lg font-medium hover:bg-gray-300 transition text-sm">
                        ❤️ Сохранить
                    </button>
                </div>
            </div>
        </div>
    );
}

/**
 * 📊 Компонент сравнения документов
 */
function ComparisonComponent({ comparison }: { comparison: any }) {
    const { hotel1, hotel2, difference, cheaper } = comparison;
    const isCheaper1 = cheaper === 'hotel1';

    return (
        <div className="flex justify-center">
            <div className="w-full max-w-2xl bg-white rounded-xl shadow-lg overflow-hidden border border-gray-100">
                <div className="bg-gradient-to-r from-purple-500 to-indigo-600 text-white p-4">
                    <h3 className="text-lg font-bold text-center">📊 Сравнение отелей</h3>
                </div>

                <div className="grid grid-cols-2 gap-4 p-4">
                    {/* Первый отель */}
                    <div className={`p-4 rounded-lg border-2 ${isCheaper1 ? 'border-green-500 bg-green-50' : 'border-gray-200 bg-gray-50'}`}>
                        <h4 className="font-bold text-lg truncate">{hotel1.name}</h4>
                        <p className="text-sm text-gray-600 mb-2">
                            📍 {hotel1.city}, {hotel1.country}
                        </p>
                        <div className="flex items-baseline gap-1">
                            <span className="text-3xl font-bold text-gray-800">
                                {hotel1.price1 || hotel1.pricePerNight || '—'}
                            </span>
                            <span className="text-gray-600">₽/ночь</span>
                        </div>
                        {isCheaper1 && (
                            <div className="mt-2 px-3 py-1 bg-green-500 text-white rounded-full text-xs font-medium w-fit">
                                ✅ Дешевле на {difference}₽
                            </div>
                        )}
                    </div>

                    {/* Второй отель */}
                    <div className={`p-4 rounded-lg border-2 ${!isCheaper1 ? 'border-green-500 bg-green-50' : 'border-gray-200 bg-gray-50'}`}>
                        <h4 className="font-bold text-lg truncate">{hotel2.name}</h4>
                        <p className="text-sm text-gray-600 mb-2">
                            📍 {hotel2.city}, {hotel2.country}
                        </p>
                        <div className="flex items-baseline gap-1">
                            <span className="text-3xl font-bold text-gray-800">
                                {hotel2.price2 || hotel2.pricePerNight || '—'}
                            </span>
                            <span className="text-gray-600">₽/ночь</span>
                        </div>
                        {!isCheaper1 && (
                            <div className="mt-2 px-3 py-1 bg-green-500 text-white rounded-full text-xs font-medium w-fit">
                                ✅ Дешевле на {difference}₽
                            </div>
                        )}
                    </div>
                </div>

                <div className="px-4 py-4 bg-gradient-to-r from-purple-50 to-indigo-50 border-t border-gray-200 text-center">
                    <p className="text-sm text-gray-700 font-medium">
                        💡 Разница в цене: <span className="font-bold text-lg text-purple-600">{difference}₽</span>
                    </p>
                </div>
            </div>
        </div>
    );
}

export default App;
